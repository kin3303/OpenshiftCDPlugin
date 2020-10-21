$[/myProject/scripts/preamble]  
$[/myProject/scripts/ImportFromTemplateEx]
$[/myProject/scripts/OpenshiftClientEx]

// Input parameters
def osTemplateYaml = '''$[osTemplateYaml]'''.trim()
def osTemplateValues =  '''$[templateParamValues]'''.trim().split('/').collect{it as String}
def envProjectName = '$[envProjectName]'
def environmentNames =  "$[envName]".split(",").collect{it as String}
def clusterNames =  "$[clusterName]".split(",").collect{it as String}
def efClient = new EFClient()
def ocpClient = new OpenshiftClientEx()
def importUtil = new ImportFromTemplateEx()


[environmentNames,clusterNames].transpose().each { environmentName, clusterName ->
    //Check Environment
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
        println "Either specify all the parameters required to identify the OpenShift-backed ElectricFlow cluster (environment project name, environment name, and cluster name) where the newly created microservice(s) will be deployed. Or do not specify any of the cluster related parameters in which case the service mapping to a cluster will not be created for the microservice(s)."
        System.exit(-1)
    }
}

[environmentNames,clusterNames,osTemplateValues].transpose().each { environmentName, clusterName, osTemplateValue->
    //Get API Access Information
    def pluginConfig = ocpClient.getPluginConfig(efClient, clusterName, envProjectName, environmentName)
    def accessToken = ocpClient.retrieveAccessToken (pluginConfig)

    def clusterParameters = efClient.getProvisionClusterParameters((String)clusterName,(String)envProjectName, (String)environmentName)
    def clusterEndpoint = pluginConfig.clusterEndpoint
    def namespace = clusterParameters.project

    // Parse Template
    def templateResolved = importUtil.resolveTemplateByParameters(osTemplateYaml, osTemplateValue)
    def resourceMap = importUtil.importFromTemplate(templateResolved)

    def bcs = resourceMap['buildconfigs']
    if(bcs) {
        bcs.each { bc ->
            def buildConfigName = bc.metadata.name
            ocpClient.startBuild(clusterEndpoint, namespace,accessToken, buildConfigName)
        }
    }
}
