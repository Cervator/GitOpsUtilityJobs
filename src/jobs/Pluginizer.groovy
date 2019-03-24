def jobName = "Pluginizer"

job(jobName) {
    description('Updates plugins as per a list from a given Git repo.')
    label("master")
    scm {
        git {
            remote {
                // TODO: Consider varying this env var to point it at the repo holding the desired manifest
                url(System.getenv("GITOPS_MANIFEST_REPO"))
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

        if (isPluginsInstalled) {
            instance.save()
            println "Calling safe restart of Jenkins after installing/updating the plugins: " + pluginsInstalled
            instance.doSafeRestart()
        } else {
          println "No plugins updated, no restart needed"
        }
        ''')
    }
}
