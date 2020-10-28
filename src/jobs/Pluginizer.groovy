import jenkins.model.Jenkins

def jobName = "Pluginizer"

job("GitOpsUtility/$jobName") {
    description('Updates plugins as per a list from a given Git repo.')
    label("master")
    scm {
        git {
            remote {
                // TODO: Consider varying this env var to point it at the repo holding the desired manifest
                def envVars = Jenkins.instance.getGlobalNodeProperties()[0].getEnvVars()
                println "Setting up job with a GITOPS_MANIFEST_REPO target of " + envVars['GITOPS_MANIFEST_REPO']
                url(envVars['GITOPS_MANIFEST_REPO'])
                // TODO: If using private repos either replace with a username that has access to the DSL repo or make the env var
                // Note that in a brand new generated Jenkins the credential will need to be created before this will work
                def dslRepoCred = System.getenv("GIT_CREDENTIAL")
                if (dslRepoCred != null && !dslRepoCred.equals("")) {
                    println '"GIT_CREDENTIAL" was set - using credential: ' + dslRepoCred
                    credentials(dslRepoCred)
                } else {
                    println 'No "GIT_CREDENTIAL" set, so assuming public repository'
                }

              // This is an optional ability to use branches in the dsl/manifest repos if desired
              def targetBranch = getBinding().getVariables()["GITOPS_PLATFORM_LEVEL"] ?: "master"
              println "Making job $jobName with a target manifest using branch: $targetBranch"
              branch(targetBranch)
            }
        }
    }
    steps {
        systemGroovyCommand ('''
        import jenkins.model.Jenkins

        def instance = Jenkins.getInstance()
        def updateCenter = instance.getUpdateCenter()
        def pluginManager = instance.getPluginManager()
        def isPluginsInstalled = false
        def pluginsInstalled = ""

        def env = System.getenv()

        // Assume a properties file is present that contains environment specific details
        Properties props = new Properties()
        String  pathVar = build.getEnvironment(listener).get('WORKSPACE')
        File workspacePath = new File(pathVar)
        File someFile = new File(workspacePath , "stack.properties")
        props.load(someFile.newReader())
        println "Give props! " + props

        String pluginsString = props.extraPlugins
        def plugins = pluginsString.split()
        println "The plugins to passed in be installed are: " + plugins

        Map<String, Map<String, String>> independentPlugins = [:]

        // Look for a custom plugin - TODO support multiple in YAML manifest version
        if (props.containsKey("customPlugin")) {
            println "Found a custom plugin!"
            String pluginDetails = props.customPlugin
            List listedDetails = pluginDetails.split()
            def name = listedDetails[0]
            def version = listedDetails[1]
            def packaging = listedDetails[2]
            def url = listedDetails[3]
            println "Got the custom plugin: " + name + ", " + version + ", " + packaging + ", " + url

            // This may show a syntax warning but plain name intended as a variable just became literal "name"
            independentPlugins << [ "$name" : ["version" : version, "packaging" : packaging, "url" : url]]
        }

        println "Independent plugins: " + independentPlugins

        def isIndependentPluginsInstalled = false
        def independentPluginsInstalled = ""

        println "The plugins to passed in be installed are: " + plugins

        updateCenter.updateAllSites()

        plugins.each {
            if (!pluginManager.getPlugin(it)) {
                println "Checking update center for plugin: " + it
                def plugin = updateCenter.getPlugin(it)
                if (plugin) {
                    println "Installing plugin " + it
                    def installFuture = plugin.deploy()
                    while (!installFuture.isDone()) {
                        println "Waiting for plugin install: " + it
                        sleep(3000)
                    }
                    println "Successfully installed: " + it
                    isPluginsInstalled = true
                    pluginsInstalled += it + ", "
                } else {
                    println "Plugin " + it + " failed to be installed using the update center."
                }
            } else {
                println "The plugin " + it + " was already installed, skipped"
            }
        }

        def runBash(def command) {
            def process = ['bash', '-c', command.toString().trim()].execute()
            def output = new StringBuilder(), error = new StringBuilder()
            process.waitForProcessOutput(output, error)
            output = output.toString().trim()
            error = error.toString().trim()
            return [stdOut: output, stdErr: error, exitCode: process.exitValue(), command: command]
        }

        independentPlugins.each {
            if (!pluginManager.getPlugin(it.key)) {
                println "Checking info for plugin: " + it.key + " " + it.value

                def pluginDownloadCmd = runBash("""
        curl -o /tmp/${it.key}-${it.value.version}.${it.value.packaging} ${it.value.url}${it.key}-${it.value.version}.${it.value.packaging}
        """)

                if (pluginDownloadCmd.exitCode != 0) {
                    println  "Failed to download plugin. Got error: ${pluginDownloadCmd.stdErr} - Used the command: ${pluginDownloadCmd.command}"
                    return 1
                }


                def pluginDeployCmd = runBash("""
        cp /tmp/${it.key}-${it.value.version}.${it.value.packaging} /var/jenkins_home/plugins
        """)

                if (pluginDeployCmd.exitCode != 0) {
                    println  "Failed to deploy the plugin. Got error: ${pluginDeployCmd.stdErr} - Used the command: ${pluginDeployCmd.command}"
                    return 1
                }

                println "Successfully deployed: " + it.key
                isIndependentPluginsInstalled = true
                independentPluginsInstalled += it.key + ", "
            } else {
                println "The plugin " + it.key + " was already installed, skipped"
            }

        }
        if (isPluginsInstalled || isIndependentPluginsInstalled) {
            instance.save()
            println "Calling safe restart of Jenkins after installing/updating the plugins: " + pluginsInstalled + " " + independentPluginsInstalled + " ."
            instance.doSafeRestart()
        } else {
          println "No plugins updated, no restart needed"
        }
        ''')
    }
}
