$[/myProject/scripts/preamble]
$[/myProject/scripts/ImportFromTemplate]

// Input parameters
def osTemplateYaml = '''$[osTemplateYaml]'''.trim()
def osTemplateValues =  '''$[templateParamValues]'''.trim().split('/').collect{it as String}
def projectName = '$[projName]'
def envProjectName = '$[envProjectName]'
def environmentNames =  "$[envName]".split(",").collect{it as String}
def clusterNames =  "$[clusterName]".split(",").collect{it as String}
def applicationScoped = '$[application_scoped]'
def applicationName = '$[application_name]'

EFClient efClient = new EFClient()

if(efClient.toBoolean(applicationScoped)) {
    if (!applicationName) {
        println "Application name is required for creating application-scoped microservices"
        System.exit(-1)
    }
} else {
    //reset application name since its not relevant if application_scoped is not set
    applicationName = null
}

 [environmentNames,clusterNames].transpose().each { environmentName, clusterName ->
    if (envProjectName && environmentName && clusterName) {
        def clusters = efClient.getClusters(envProjectName, environmentName)
        def cluster = clusters.find {
            it.clusterName == clusterName
        }
        if (!cluster) {
            println "Cluster '${clusterName}' does not exist in '${environmentName}' environment!"
            System.exit(-1)
        }
        if (cluster.pluginKey != 'EC-OpenShift') {
            println "Wrong cluster type: ${cluster.pluginKey}"
            println "ElectricFlow cluster '${clusterName}' in '${environmentName}' environment is not backed by a OpenShift-based cluster."
            System.exit(-1)
        }
    } else if (envProjectName || environmentName || clusterName) {
        // If any of the environment parameters are specified then *all* of them must be specified.
        println "Either specify all the parameters required to identify the OpenShift-backed ElectricFlow cluster (environment project name, environment name, and cluster name) where the newly created microservice(s) will be deployed. Or do not specify any of the cluster related parameters in which case the service mapping to a cluster will not be created for the microservice(s)."
        System.exit(-1)
    }
}


[environmentNames,clusterNames,osTemplateValues].transpose().each { environmentName, clusterName, osTemplateValue ->
    def param2value = [:]

    if (osTemplateValue) {
        osTemplateValue = osTemplateValue.trim()
        def values = osTemplateValue.split(/\s*,\s*/)
        values.each { parameterAndValue ->
            if (parameterAndValue.contains('=')) {
                String[] parameterAndValueSplitted = parameterAndValue.split(/\s*=\s*/)
                param2value.put(parameterAndValueSplitted[0].trim(), parameterAndValueSplitted[1].trim())
            }
        }
    }

    def importFromTemplate = new ImportFromTemplate()

    def templateResolved = importFromTemplate.resolveTemplateByParameters(osTemplateYaml, param2value)
    def services = importFromTemplate.importFromTemplate(templateResolved)
    importFromTemplate.saveToEF(services, projectName, envProjectName, environmentName, clusterName, applicationName)
}
