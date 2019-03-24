def jobName = "Librarizer"

job(jobName) {
    description('Configures shared pipeline libraries from a manifest in a given Git repo.')
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
        import jenkins.plugins.git.GitSCMSource
        import jenkins.plugins.git.traits.BranchDiscoveryTrait
        import org.jenkinsci.plugins.workflow.libs.GlobalLibraries
        import org.jenkinsci.plugins.workflow.libs.LibraryConfiguration
        import org.jenkinsci.plugins.workflow.libs.SCMSourceRetriever

        // This supports having different Jenkins masters target different parts of the manifest - can be skipped or ignored
        def context = System.getenv("GITOPS_JENKINS_CONTEXT")
        println "The GITOPS_JENKINS_CONTEXT retrieved from the command line is: ${context}"

        if (context == null || context == "null") {
            context = "local"
        }

        // Assume a properties file is present that contains global stack specific details
        Properties props = new Properties()
        String  pathVar = build.getEnvironment(listener).get('WORKSPACE')
        File workspacePath = new File(pathVar)
        File someFile = new File(workspacePath , "stack.properties")
        props.load(someFile.newReader())
        println "Loaded stack-level defaults: " + props

        // Read a secondary properties file based on the configured environment tier and add/overwrite values if present
        File someFile2 = new File(workspacePath , "tiers/$context/tier.properties")
        if (someFile2.exists()) {
            props.load(someFile2.newReader())
            println "Loaded additional tier-specific overrides, updated to: " + props
        } else {
            println "Skipping tier-level customization, $someFile2 not found"
        }

        // TODO: A credential is needed to access the target repo containing the library. In this case we base it on a defined group name
        def credentialUser = props.groupName + "-user"

        List libraries = [] as ArrayList

        // See if we have any shared libraries defined via prop file
        if (props.sharedLibraries == null || props.sharedLibraries.trim().equals("")) {
            println "No shared libraries defined via the sharedLibraries prop, skipping that part"
        } else {
            // Libraries to load from a single space separated property of repo URLs
            // With a one liner we assume a bunch of things. Use the file based approach for fancier config
            props.sharedLibraries.split().each {
                println "The library remote retrieved is: $it with $credentialUser for access"

                def repoName = it.substring(it.lastIndexOf('/') + 1)
                if (repoName.contains('.')) {
                    repoName = repoName.substring(0, repoName.lastIndexOf('.'))
                }
                println "Extracted the repo name to use for the library name: $repoName"

                def defaultVersion = 'master'

                def scm = new GitSCMSource(it)
                scm.credentialsId = credentialUser
                scm.traits = [new BranchDiscoveryTrait()]
                def retriever = new SCMSourceRetriever(scm)

                def libraryToAdd = new LibraryConfiguration(repoName, retriever)
                libraryToAdd.defaultVersion = defaultVersion
                libraries << libraryToAdd

                println "Going to add Pipeline Global Shared Library $repoName from ${it}"
            }
        }

        // Look for a directory full of shared lib definition files at the global (stack) level
        File sharedLibStackDir = new File(workspacePath , "libs")
        if (sharedLibStackDir.exists()) {
            println "There's a directory for shared libs at the stack level - checking out the contents"
            sharedLibStackDir.eachFile {
                Properties sharedLibProps = new Properties()
                sharedLibProps.load(it.newReader())
                println "Found a shared lib definition with the following props: $sharedLibProps"

                def scm = new GitSCMSource(sharedLibProps.repo)
                if (sharedLibProps.credential != null && !sharedLibProps.credential.equalsIgnoreCase("N/A")) {
                    println "Shared library seemingly has a credential, adding in as: " + sharedLibProps.credential
                    scm.credentialsId = sharedLibProps.credential
                }
                scm.traits = [new BranchDiscoveryTrait()]
                def retriever = new SCMSourceRetriever(scm)

                def libraryToAdd = new LibraryConfiguration(sharedLibProps.name, retriever)
                libraryToAdd.defaultVersion = sharedLibProps.defaultVersion
                libraryToAdd.implicit = sharedLibProps.implicit.trim().equalsIgnoreCase("true") ? true : false
                libraryToAdd.allowVersionOverride = sharedLibProps.allowVersionOverride.trim().equalsIgnoreCase("true") ? true : false
                libraryToAdd.includeInChangesets = sharedLibProps.includeInChangesets.trim().equalsIgnoreCase("true") ? true : false
                libraries << libraryToAdd
            }
        } else {
            println "$sharedLibStackDir not found so not loading any shared libraries from the stack level"
        }

        // Look for a directory full of shared lib definition files at the tier level
        File sharedLibTierDir = new File(workspacePath , "tiers/$context/libs")
        if (sharedLibTierDir.exists()) {
            println "There's a directory for shared libs at the tier level - checking out the contents"
            sharedLibTierDir.eachFile {
                Properties sharedLibProps = new Properties()
                sharedLibProps.load(it.newReader())
                println "Found a shared lib definition with the following props: $sharedLibProps"

                def scm = new GitSCMSource(sharedLibProps.repo)
                if (sharedLibProps.credential != null && !sharedLibProps.credential.equalsIgnoreCase("N/A")) {
                    println "Shared library seemingly has a credential, adding in as: " + sharedLibProps.credential
                    scm.credentialsId = sharedLibProps.credential
                }
                scm.traits = [new BranchDiscoveryTrait()]
                def retriever = new SCMSourceRetriever(scm)

                def libraryToAdd = new LibraryConfiguration(sharedLibProps.name, retriever)
                libraryToAdd.defaultVersion = sharedLibProps.defaultVersion
                libraryToAdd.implicit = sharedLibProps.implicit.trim().equalsIgnoreCase("true") ? true : false
                libraryToAdd.allowVersionOverride = sharedLibProps.allowVersionOverride.trim().equalsIgnoreCase("true") ? true : false
                libraryToAdd.includeInChangesets = sharedLibProps.includeInChangesets.trim().equalsIgnoreCase("true") ? true : false
                libraries << libraryToAdd
            }
        } else {
            println "$sharedLibTierDir not found so not loading any shared libraries from the tier level"
        }

        // Observation: Jenkins config only shows the SCM info expanded for the last configured shared lib, that's OK!
        GlobalLibraries global_settings = Jenkins.instance.getExtensionList(GlobalLibraries.class)[0]
        global_settings.libraries = libraries
        global_settings.save()
        ''')
    }
}
