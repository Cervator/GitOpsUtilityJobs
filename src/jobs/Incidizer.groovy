def jobName = "HoboHealthChecker"

pipelineJob(jobName) {
    description('Creates a job that does a simplistic health check of a target URL.')

    definition {
      	cps {
            script('''
                @Library('HoboHealthCheck') _

                pipeline {
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

                    agent { label 'master' }
                    environment {
                        success = hoboWebCheck("http://other-widget.jx-go2group-other-widget-pr-5.35.196.145.46.nip.io", "Cool Jenkins X demo")
                    }
                    stages {
                        stage('post') {
                            when {
                                environment name: 'success', value: 'false'
                            }
                            steps {
                                echo "Success? $success"
                                error "The hobos call for aid!"
                            }
                        }
                    }
                    post {
                        success {
                            echo 'All is well no need to do anything'
                        }
                        failure {
                            echo 'This will run only if failed'
                            opsgenie(tags: "failure, critical", teams: "Hobo GitOps", priority:"P3")
                        }
                    }
                }
            '''.stripIndent())
            sandbox()
        }
    }
}
