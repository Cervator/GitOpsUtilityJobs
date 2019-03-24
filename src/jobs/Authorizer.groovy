def jobName = "Authorizer"

job(jobName) {
    description('Configures an authorization matrix based on some details from a Git repo')
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
    triggers {
        // Jenkins restart trigger
        hudsonStartupTrigger {
            // Trigger based on the master
            label("master")
            // Seconds before running
            quietPeriod("10")
            // Not used but required as part of dynamic DSL (I think)
            nodeParameterName("master")
            // Run on initial connection
            runOnChoice("true")
        }
    }
    steps {
        systemGroovyCommand ('''
        import jenkins.model.*
        import groovy.transform.Field

        def instance = Jenkins.getInstance()

        def env = System.getenv()

        // Assume a properties file is present that contains global stack specific details
        Properties props = new Properties()
        String  pathVar = build.getEnvironment(listener).get('WORKSPACE')
        File workspacePath = new File(pathVar)
        File someFile = new File(workspacePath , "stack.properties")
        props.load(someFile.newReader())
        println "Loaded stack-level defaults: " + props

        // Read a secondary properties file based on the configured environment tier and add/overwrite values if present
        def tierName = env['GITOPS_JENKINS_CONTEXT']
        File someFile2 = new File(workspacePath , "tiers/$tierName/tier.properties")
        if (someFile2.exists()) {
            props.load(someFile2.newReader())
            println "Loaded additional tier-specific overrides, updated to: " + props
        } else {
            println "Skipping tier-level customization, $someFile2 not found"
        }

        def givenGroupName = props.groupName

        def readers = props.readers
        if (readers != null) {
            if (!readers.trim().equals("")) {
                readers.split().each {
                    println "Granting read role rights to $it"
                    addReadRole(it)
                }
            } else {
                println "Readers was set empty - not leaving anybody with plain read rights"
            }
        } else {
            println "Granting default read role rights to $givenGroupName-read due to no explicit 'readers' setting"
            addReadRole("$givenGroupName-read")
        }

        def writers = props.writers
        if (writers != null) {
            if (!writers.trim().equals("")) {
                writers.split().each {
                    println "Granting write role rights to $it"
                    addWriteRole(it)
                }
            } else {
                println "Writers was set empty - not leaving anybody with plain write rights"
            }
        } else {
            println "Granting default write role rights to $givenGroupName-write due to no explicit 'writers' setting"
            addWriteRole("$givenGroupName-write")
        }

        def admins = props.admins
        if (admins != null) {
            if (!admins.trim().equals("")) {
                admins.split().each {
                    println "Granting admin role rights to $it"
                    addAdminRole(it)
                }
            } else {
                println "Admins was set empty - not leaving anybody with plain admin rights"
            }
        } else {
            println "Granting default admin role rights to $givenGroupName-admin due to no explicit 'admins' setting"
            addAdminRole("$givenGroupName-admin")
        }

        def superAdmins = props.superAdmins
        if (superAdmins != null) {
            if (!superAdmins.trim().equals("")) {
                superAdmins.split().each {
                    println "Granting super admin role rights to $it"
                    addSuperAdminRole(it)
                }
            } else {
                println "superAdmins was set empty - not leaving anybody with plain super admin rights"
            }
        } else {
            println "Granting default super admins role rights to $givenGroupName-jenkins-admin due to no explicit 'superAdmins' setting"
            addSuperAdminRole("$givenGroupName-jenkins-admin")
        }

        // Jenkins-Groovy is weird - need the @Field to make this work inside the methods later (lack of 'def' not enough)
        @Field strategy = new hudson.security.GlobalMatrixAuthorizationStrategy()

        // Assign super admin rights to a global jenkins admin group (if it exists - could even be a user)
        addSuperAdminRole('jenkins-administrators')

        /**
         * Assign read-level rights to the given user or group. Generally just lets you see stuff.
         * @param name name of the user or group
         */
        def addReadRole(name) {
            strategy.add(hudson.model.Hudson.READ, name)   // Overall read
            strategy.add(hudson.model.View.READ, name)     // View read
            strategy.add(hudson.model.Item.READ, name)     // Job read
            strategy.add(hudson.model.Item.WORKSPACE, name)
            strategy.add(hudson.model.Item.DISCOVER, name)
            strategy.add(com.cloudbees.plugins.credentials.CredentialsProvider.VIEW, name)
        }

        /**
         * Assign write-level rights to the given user or group. Generally allows running jobs.
         * @param name name of the user or group
         */
        def addWriteRole(name) {
            // Write extends read
            addReadRole(name)

            strategy.add(hudson.model.Item.BUILD, name)
            strategy.add(hudson.model.Item.CANCEL, name)

            strategy.add(hudson.model.Run.UPDATE, name)
            strategy.add(hudson.model.Computer.BUILD, name)
            strategy.add(org.jenkinsci.plugins.workflow.cps.replay.ReplayAction.REPLAY, name)
        }

        /**
         * Assign admin-level rights to the given user or group. This is more an admin-lite or poweruser.
         * @param name of the user or group
         */
        def addAdminRole(name) {
            // Admin extends write
            addWriteRole(name)

            strategy.add(hudson.model.View.CONFIGURE, name)
            strategy.add(hudson.model.View.CREATE, name)
            strategy.add(hudson.model.View.DELETE, name)

            strategy.add(hudson.model.Item.CONFIGURE, name)
            strategy.add(hudson.model.Item.CREATE, name)
            strategy.add(hudson.model.Item.DELETE, name)

            strategy.add(hudson.model.Run.DELETE, name)

            strategy.add(hudson.model.Computer.CONFIGURE, name)
            strategy.add(hudson.model.Computer.CONNECT, name)
            strategy.add(hudson.model.Computer.CREATE, name)
            strategy.add(hudson.model.Computer.DELETE, name)
            strategy.add(hudson.model.Computer.DISCONNECT, name)

            strategy.add(hudson.scm.SCM.TAG, name)
        }

        /**
         * Assign super admin-level rights to the given user or group.
         * TODO: Rename to plain admin later if admin-lite becomes write
         * @param name of the user or group
         */
        def addSuperAdminRole(name) {
            // For super admin this single right gives you all the things
            strategy.add(hudson.model.Hudson.ADMINISTER, name)
        }

        instance.setAuthorizationStrategy(strategy)
        instance.save()
        ''')
    }
}
