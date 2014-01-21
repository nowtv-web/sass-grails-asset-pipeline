/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package asset.pipeline.sass

import asset.pipeline.AssetHelper
import org.mozilla.javascript.Context
import org.mozilla.javascript.JavaScriptException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.NativeArray
import org.springframework.core.io.ClassPathResource
import groovy.util.logging.Log4j
import asset.pipeline.CacheManager
import org.jruby.embed.LocalVariableBehavior;
import org.jruby.embed.ScriptingContainer;
import org.codehaus.groovy.grails.plugins.GrailsPluginUtils

@Log4j
class SassProcessor {
    public static final java.lang.ThreadLocal threadLocal = new ThreadLocal();
    ScriptingContainer container
    ClassLoader classLoader
    def precompilerMode

    SassProcessor(precompiler=false) {
        this.precompilerMode = precompiler
        try {
           container = new ScriptingContainer(LocalVariableBehavior.PERSISTENT);
            container.runScriptlet(buildInitializationScript())
            def workDir = new File("target/assets")
            if(!workDir.exists()) {
                workDir.mkdir()
            }
            container.put("to_path",workDir.canonicalPath)
            loadPluginContextPaths()
        } catch (Exception e) {
            throw new Exception("SASS Engine initialization failed.", e)
        } finally {
            try {
                Context.exit()
            } catch (IllegalStateException e) {}
        }
    }

    private String buildInitializationScript() {
        StringWriter raw = new StringWriter();
        PrintWriter script = new PrintWriter(raw);
        script.println("if !defined?(Compass)");
        script.println("require 'rubygems'                                                         ");
        script.println("require 'sass'                                                          ");
        script.println("require 'sass/plugin'                                                          ");
        script.println("require 'compass'                                                          ");
        script.println("end");
        script.println("Compass.reset_configuration!");
        script.println("frameworks = Dir.new(Compass::Frameworks::DEFAULT_FRAMEWORKS_PATH).path    ");
        script.println("Compass::Frameworks.register_directory(File.join(frameworks, 'compass'))   ");
        script.println("Compass::Frameworks.register_directory(File.join(frameworks, 'blueprint')) ");
        script.println("Compass.configure_sass_plugin!                                             ");

        script.flush();

        return raw.toString();
    }

    private loadPluginContextPaths() {
        container.runScriptlet("PLUGIN_CONTEXT_PATHS = {}  if !defined?(PLUGIN_CONTEXT_PATHS)")
        for(plugin in GrailsPluginUtils.pluginInfos) {
            def pluginContextPath = plugin.pluginDir.getPath()
            container.put("plugin_context", pluginContextPath)
            container.put("plugin_name", plugin.name)
            container.runScriptlet("PLUGIN_CONTEXT_PATHS[plugin_name] = plugin_context")
        }
    }

    def process(input, assetFile) {
        if(!this.precompilerMode) {
            threadLocal.set(assetFile);
        }
        def assetRelativePath = relativePath(assetFile.file)
        // def paths = AssetHelper.scopedDirectoryPaths(new File("grails-app/assets").getAbsolutePath())

        // paths += [assetFile.file.getParent()]
        def paths = AssetHelper.getAssetPaths()
        def relativePaths = paths.collect { [it,assetRelativePath].join(AssetHelper.DIRECTIVE_FILE_SEPARATOR)}
        // println paths
        paths = relativePaths + paths


        def pathstext = paths.collect{
            def p = it.replaceAll("\\\\", "/")
            if (p.endsWith("/")) {
                "${p}"
            } else {
                "${p}/"
            }
        }.join(",")
        container.put("assetFilePath", assetFile.file.canonicalPath)
        container.put("load_paths", pathstext)
        container.put("working_path", assetFile.file.getParent())

        def outputFileName = new File("target/assets/${AssetHelper.fileNameWithoutExtensionFromArtefact(assetFile.file.name,assetFile)}.${assetFile.compiledExtension}".toString()).canonicalPath
        container.put("file_dest", outputFileName)
        container.runScriptlet("""
        Compass.add_configuration(
        {
        :project_path => working_path,
        :sass_path => working_path,
        :css_path => to_path,
        :additional_import_paths => load_paths.split(',')
        },
        'Grails' # A name for the configuration, can be anything you want
        )
        """)

        def configFile = new File(assetFile.file.getParent(), "config.rb")
        if(configFile.exists()) {
            container.put('config_file',configFile.canonicalPath)
        } else {
            container.put('config_file',null)
        }

        container.runScriptlet("""
        Compass.configure_sass_plugin!
        Dir.chdir(working_path) do
        Compass.add_project_configuration config_file if config_file
        Compass.compiler.compile_if_required(assetFilePath, file_dest)
        end
        """)

        def outputFile = new File(outputFileName)
        if(outputFile.exists()) {
            if(assetFile.encoding) {
                return outputFile.getText(assetFile.encoding)
            }
            return outputFile.getText()
        } else {
            return input
        }
    }

    def relativePath(file, includeFileName=false) {
        def path
        if(includeFileName) {
            path = file.class.name == 'java.io.File' ? file.getCanonicalPath().split(AssetHelper.QUOTED_FILE_SEPARATOR) : file.file.getCanonicalPath().split(AssetHelper.QUOTED_FILE_SEPARATOR)
        } else {
            path = file.getParent().split(AssetHelper.QUOTED_FILE_SEPARATOR)
        }

        def startPosition = path.findLastIndexOf{ it == "grails-app" }
        if(startPosition == -1) {
            startPosition = path.findLastIndexOf{ it == 'web-app' }
            if(startPosition+2 >= path.length) {
                return ""
            }
            path = path[(startPosition+2)..-1]
        } else {
            if(startPosition+3 >= path.length) {
               return ""
            }
            path = path[(startPosition+3)..-1]
        }

        return path.join(AssetHelper.DIRECTIVE_FILE_SEPARATOR)
    }
}
