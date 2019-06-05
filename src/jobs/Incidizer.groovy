def jobName = "HoboHealthChecker"

pipelineJob(jobName) {
    description('Creates a job that does a simplistic health check of a target URL.')

    definition {
      	cps {
            script('''
                @Library('HoboHealthCheck') _

                pipeline {
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
