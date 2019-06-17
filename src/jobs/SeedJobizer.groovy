def jobName = "SeedJobForSeedJobs"

job("GitOpsUtility/$jobName") {
    description("This seed job was created by one seed job and creates other seed jobs based on some properties!")
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
        dsl {
            def seedJobDSL = """
                job('GitOpsUtility/SeedJobForActualWork') {

                    // Assume a properties file has been retrieved from the manifest that contains environment specific details
                    Properties props = new Properties()
                    String someText = readFileFromWorkspace("stack.properties")
                    props.load(new StringReader(someText))

                    description('"Final" Seed Job that makes actual end-user related jobs')
                    label("master")

            		    scm {
            		        git {
                            String dslRepoUrl = props.dslRepo
                            def dslRepoBranch = "master"

                            // See if a branch is indicated
                            if (dslRepoUrl.contains('@')) {
                                // By using + instead of dollar here we seem to avoid quirky errors in the parent seed job's DSL parsing
                                println "Found a DSL repo URL with a @ in it, expecting branch definition: " + dslRepoUrl
                                dslRepoBranch = dslRepoUrl.substring(dslRepoUrl.indexOf('@') + 1)
                                dslRepoUrl = dslRepoUrl.substring(0, dslRepoUrl.indexOf('@'))

                                // Special case: Use the GITOPS_JENKINS_CONTEXT variable if the branch is set to this value
                                if (dslRepoBranch.equalsIgnoreCase("GitOpsSpecialContext")) {
                                  println "DSL branch was set to 'GitOpsSpecialContext' so loading the context branch from the environment instead"
                                  dslRepoBranch = System.getenv("GITOPS_JENKINS_CONTEXT")
                                }
                            }

                            remote {
                                url(dslRepoUrl)
                                def dslRepoCred = System.getenv("GIT_CREDENTIAL")
                                if (dslRepoCred != null && !dslRepoCred.equals("")) {
                                    println '"GIT_CREDENTIAL" was set - using credential: ' + dslRepoCred
                                    credentials(dslRepoCred)
                                } else {
                                    println 'No "GIT_CREDENTIAL" set, so assuming public repository'
                                }
                            }

                            println "Making an inner seed job with repo " + dslRepoUrl + " and branch: " + dslRepoBranch
                            branch(dslRepoBranch)
                        }
                    }
                    triggers {
                        // Every 15 minutes poll to see if there are new changes
                        scm('H/15 * * * *')
                        // Once a day run anyway to catch and highlight manual changes
                        cron('H/60 H/24 * * *')
                    }
                    concurrentBuild(false)
                    steps {
                        dsl {
                            external "src/jobs/**/*.groovy"
                            removeAction('DELETE')
                            removeViewAction('DELETE')
                            ignoreExisting(false)
                        }
                    }
                }
            """

            text(seedJobDSL)
            removeAction('DELETE')
            removeViewAction('DELETE')
            ignoreExisting(false)
        }
    }
}
