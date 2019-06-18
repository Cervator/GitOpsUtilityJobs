def jobName = "Clearizer"

job("GitOpsUtility/$jobName") {
    description('Clears the build queue (not running builds)')
    label("master")
    steps {
        systemGroovyCommand ('''
            import hudson.model.*
            def queue = Hudson.instance.queue
            println "Queue contains ${queue.items.length} items"
            queue.clear()
            println "Queue cleared"
        ''')
    }
}
