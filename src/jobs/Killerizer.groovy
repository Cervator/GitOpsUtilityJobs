def jobName = "Killerizer"

job("GitOpsUtility/$jobName") {
    description('Thoroughly kills a specific running build for a given job, marking it as aborted')
    label("master")
    parameters {
        stringParam("JobNameParam", "Full Job Name", "Should be the fully qualified job path including folders, such as 'Some-Multibranch-Pipeline/Some-Job/master'")
        stringParam("BuildNumParam", "Build Number", "The build number that is actively running and needs to be aborted, such as '5'")
    }
    steps {
        systemGroovyCommand ("""

            import jenkins.model.Jenkins
            import hudson.model.Result

            String jobNameParam = build.buildVariableResolver.resolve("JobNameParam")
            String buildNumParam = build.buildVariableResolver.resolve("BuildNumParam")

            if (jobNameParam == null | jobNameParam.equals("")) {
                println "Can't work with a null or empty job name!"
                build.result = Result.UNSTABLE
                return
            }

            int targetBuildNum
            if (buildNumParam == null | buildNumParam.equals("")) {
                println "Can't work with a null or empty build number!"
                build.result = Result.UNSTABLE
                return
            } else {
                try {
                    targetBuildNum = Integer.parseInt(buildNumParam)
                } catch (NumberFormatException e) {
                    println "Can't parse that build number into an int: " + buildNumParam
                    build.result = Result.UNSTABLE
                    return
                }
            }

            println "Going to kill build '" + targetBuildNum + "' for job '" + jobNameParam + "'"

            def targetJob = Jenkins.instance.getItemByFullName(jobNameParam)

            if (targetJob == null) {
                println "Can't find that job! Did you remember to include any folder path?"
                build.result = Result.UNSTABLE
                return
            }

            def targetBuild = targetJob.getBuildByNumber(targetBuildNum)
            if (targetBuild == null) {
                println "Can't find that build number for the given job!"
                build.result = Result.UNSTABLE
                return
            }

            // The following approach might be needed in some cases? Doesn't work for some build types though
            //targetBuild.finish(Result.ABORTED, new java.io.IOException("Aborting build on user request"))

            // This approach certainly works in some cases. It just blindly issues a stop and cares little about the result
            targetBuild.doStop()
        """)
    }
}
