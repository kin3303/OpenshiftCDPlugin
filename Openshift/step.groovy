$[/myProject/scripts/preamble]  
$[/myProject/scripts/ImportBuildFromTemplate]
$[/myProject/scripts/OpenShiftBuildClient]


// Input parameters
def osTemplateYaml = '''$[osTemplateYaml]'''.trim()
def osTemplateValues = '''$[templateParamValues]'''.trim() 
def envProjectName = '$[envProjectName]'
def environmentName = '$[envName]'
def clusterName = '$[clusterName]'
def efClient = new EFClient()
def buildClient = new OpenShiftBuildClient()
def importFromTemplate = new ImportBuildFromTemplate()
 
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

// Parse BuildConfig
def templateResolved = importFromTemplate.resolveTemplateByParameters(osTemplateYaml, osTemplateValues) 
importFromTemplate.importFromTemplate(templateResolved)
def buildConfigs = importFromTemplate.getBuildConfigs()
def imageStreams = importFromTemplate.getImageStreams()

//Create/Update BuildConfig

def pluginConfig = buildClient.getPluginConfig(efClient, clusterName, envProjectName, environmentName)
def accessToken = buildClient.retrieveAccessToken (pluginConfig) 

def clusterParameters = efClient.getProvisionClusterParameters((String)clusterName,(String)envProjectName, (String)environmentName)

def clusterEndpoint = pluginConfig.clusterEndpoint
println "ClusterEndpoint: \n${clusterEndpoint}\n"

def namespace = clusterParameters.project
println "Namespace: \n${namespace}\n"

//def clusterEndpoint = 'https://test.letsgohomenow.com:8443/'
//def namespace = 'cbcd'
buildClient.applyConfigs(clusterEndpoint, namespace,accessToken, buildConfigs,imageStreams)








$[/myProject/scripts/preamble]

//// Input parameters
String serviceName = '$[serviceName]'
String serviceProjectName = '$[serviceProjectName]'
String applicationName = '$[applicationName]'
String clusterName = '$[clusterName]'
String clusterOrEnvProjectName = '$[clusterOrEnvProjectName]'
// default cluster project name if not explicitly set
if (!clusterOrEnvProjectName) {
    clusterOrEnvProjectName = serviceProjectName
}
String environmentName = '$[environmentName]'
String applicationRevisionId = '$[applicationRevisionId]'
String serviceEntityRevisionId = '$[serviceEntityRevisionId]'

String resultsPropertySheet = '$[resultsPropertySheet]'
if (!resultsPropertySheet) {
    resultsPropertySheet = '/myParent/parent'
}

//// -- Driver script logic to provision cluster -- //
EFClient efClient = new EFClient()
OpenShiftClient client = new OpenShiftClient()

def pluginConfig = client.getPluginConfig(efClient, clusterName, clusterOrEnvProjectName, environmentName)
String accessToken = client.retrieveAccessToken (pluginConfig)

def clusterParameters = efClient.getProvisionClusterParameters(
    clusterName,
    clusterOrEnvProjectName,
    environmentName
)

String clusterEndpoint = pluginConfig.clusterEndpoint
String namespace = clusterParameters.project

client.deployService(
        efClient,
        accessToken,
        clusterEndpoint,
        namespace,
        serviceName,
        serviceProjectName,
        applicationName,
        applicationRevisionId,
        clusterName,
        clusterOrEnvProjectName,
        environmentName,
        resultsPropertySheet,
        serviceEntityRevisionId)