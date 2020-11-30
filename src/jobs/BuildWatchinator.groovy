def jobName = "BuildWatchinator"

job("GitOpsUtility/$jobName") {
    description('Runs occasionally to check the queue for builds that have run for too long or have lost their build agent')
    label("master")
    steps {
        systemGroovyCommand('''
import jenkins.model.*
import hudson.model.*

long durationTerminationThreshold = 1000 * 60 * 30 // 30 minutes

def offlinedAgentJobs = []

// Check for offline build agents that are nonetheless still busy (broken build).
for (Node node in Jenkins.instance.nodes) {
    if (node.getComputer().isOffline() && node.getComputer().countBusy() > 0) {
        println "Found an offline node '$node' that had " + node.getComputer().countBusy() + " busy executor(s) on it"
        node.getComputer().getExecutors().each {
            println "Storing current queue executable for broken agent: " + it.getCurrentExecutable()
            // By storing this awkward String we can test against it later - not finding an obvious way to get the runId directly
            offlinedAgentJobs << it.getCurrentExecutable().toString()
        }
    }
}

// Get a list of actively running builds to check - note that this relies on the All view
def runningBuilds = Jenkins.instance.getView('All').getBuilds().findAll() { it.getResult().equals(null) }

println "Checking on running builds: " + runningBuilds
runningBuilds.each {
    println "***************************************"
    println "Checking on " + it + " of type " + it.class

    def parentProjectName = it.project.fullName
    def targetJob = Jenkins.instance.getItemByFullName(it.project.fullName)
    def durationMs = System.currentTimeMillis() - it.getTimestamp().timeInMillis
    def executor = it.getExecutor()

    println "Its parent project is $parentProjectName and it has been running for $durationMs ms on $executor"

    // Termination case one: We lost our executor but Jenkins still thinks it might come back (it won't if evicted or preempted)
    // Difficulty: If a build agent is lost the active executor appears to revert back to a flyweight one on the master ...
    // May be the case that pipeline jobs where a stage is active on an agent the pipeline itself is still counted on master
    // So as an alternative that seems to work we just compare the active build against a list earlier of broken agents
    String offlineSearchTerm = "{runId=" + parentProjectName
    //println "Checking if '$offlineSearchTerm' occurs in the offline build list"

    boolean brokenAgentOwnsJob
    for (String snippet : offlinedAgentJobs) {
        if (snippet.contains(offlineSearchTerm)) {
            println "Found that job is in the offline agent broken build list!"
            brokenAgentOwnsJob = true
        }
    }

    if (brokenAgentOwnsJob) {
        println "Looks like this build lost its executor to offline land - terminating as aborted then retrying"
        executor.interrupt(Result.ABORTED, new CauseOfInterruption() {
            @Override
            String getShortDescription() {
                return "*** Build terminated as its executor appears to have gone offline. Will retry ***"
            }
        })

        println "Going to trigger a rebuild for $targetJob"
        def cause = new Cause.UpstreamCause(build)
        def causeAction = new CauseAction(cause)
        Jenkins.instance.queue.schedule(targetJob, 0, causeAction)

        // Mark the BuildWatchinator itself as unstable as a way to indicate an action was taken this time around
        build.result = Result.UNSTABLE
    } else if (durationMs > durationTerminationThreshold) {
        // Termination case two: The running process has exceeded a defined max duration
        println "Found this build had exceeded the duration threshold: $durationMs > $durationTerminationThreshold - terminating and marking as failed"
        executor.interrupt(Result.FAILURE, new CauseOfInterruption() {
            @Override
            String getShortDescription() {
                return "*** Build terminated for exceeding max duration threshold of " + durationTerminationThreshold + " ms ***"
            }
        })

        // Mark the BuildWatchinator itself as unstable as a way to indicate an action was taken this time around
        build.result = Result.UNSTABLE
    }
}
return null

        ''')
    }

    triggers {
        cron("H/10 * * * *")
    }
}
