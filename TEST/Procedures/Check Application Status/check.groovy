$[/myProject/scripts/preamble]  
$[/myProject/scripts/ImportFromTemplateEx]
$[/myProject/scripts/OpenshiftClientEx]
 
// Input parameters
def appName = "$[appName]".trim() 
def envProjectName = '$[envProjectName]'
def environmentName =  "$[envName]".trim()
def clusterName =  "$[clusterName]".trim()
def efClient = new EFClient()
def ocpClient = new OpenshiftClientEx()
def importUtil = new ImportFromTemplateEx()

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
 
//Get API Access Information
def pluginConfig = ocpClient.getPluginConfig(efClient, clusterName, envProjectName, environmentName)
def accessToken = ocpClient.retrieveAccessToken (pluginConfig)
def clusterParameters = efClient.getProvisionClusterParameters((String)clusterName,(String)envProjectName, (String)environmentName)
def clusterEndpoint = pluginConfig.clusterEndpoint
def namespace = clusterParameters.project

println "Cluster EndPoint : $clusterEndpoint"
 
String result = ocpClient.checkExistingApplication(clusterEndpoint, namespace, appName, accessToken)
//efClient.setEFProperty("/myJob/deploymentType", result)
efClient.setEFProperty("/myPipelineRuntime/deploymentType", result)