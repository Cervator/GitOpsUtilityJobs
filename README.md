A simple Jenkins Job DSL repository containing example utility jobs to help maintain Jenkins itself, sometimes using configuration retrieved from a separate manifest repo.

* Authorizer - configures an auth matrix
* GentleRestartJob - restarts Jenkins, gently
* Librarizer - configures pipeline libraries
* Pluginizer - installs plugins as per a retrieved list
* SeedJobizer - creates a secondary seed job that in turn can create a tertiary seed job based on a manifest! Yep it is seed jobs all the way down.
