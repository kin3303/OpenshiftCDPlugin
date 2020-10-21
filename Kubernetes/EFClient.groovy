
/**
 * ElectricFlow API client
 */
public class EFClient extends BaseClient {
    static final String REST_VERSION = 'v1.0'

    def getServerUrl() {
        def commanderServer = System.getenv('COMMANDER_SERVER')
        def secure = Integer.getInteger("COMMANDER_SECURE", 1).intValue()
        def protocol = secure ? "https" : "http"
        def commanderPort = secure ? System.getenv("COMMANDER_HTTPS_PORT") : System.getenv("COMMANDER_PORT")
        def url = "$protocol://$commanderServer:$commanderPort"
        logger DEBUG, "Using ElectricFlow server url: $url"
        url
    }

    Object doHttpGet(String requestUri, boolean failOnErrorCode = true, def query = null) {
        def sessionId = System.getenv('COMMANDER_SESSIONID')
        doHttpRequest(GET, getServerUrl(), requestUri, ['Cookie': "sessionId=$sessionId"],
                failOnErrorCode, /*requestBody*/ null, query)
    }

    Object doHttpPost(String requestUri, Object requestBody, boolean failOnErrorCode = true, def query = null) {
        def sessionId = System.getenv('COMMANDER_SESSIONID')
        doHttpRequest(POST, getServerUrl(), requestUri, ['Cookie': "sessionId=$sessionId"], failOnErrorCode, requestBody, query)
    }

    Object doHttpPut(String requestUri, Object requestBody, boolean failOnErrorCode = true, def query = null) {
        def sessionId = System.getenv('COMMANDER_SESSIONID')
        doHttpRequest(PUT, getServerUrl(), requestUri, ['Cookie': "sessionId=$sessionId"], failOnErrorCode, requestBody, query)
    }

    private def payloadToJson(payload) {
        def refinedPayload = [:]
        payload.each {k, v ->
            if (v != null) {
                refinedPayload[k] = v
            }
        }
        def json = JsonOutput.toJson(refinedPayload)
    }

    Object doRestPost(String requestUri, Map payload, boolean failOnErrorCode = true, def query = null) {
        def json = payloadToJson(payload)
        doHttpPost(requestUri, json, failOnErrorCode, query)
    }

    Object doRestPut(String requestUri, Map payload, boolean failOnErrorCode = true, def query = null) {
        def json = payloadToJson(payload)
        doHttpPut(requestUri, json, failOnErrorCode, query)
    }

    def getConfigValues(def configPropertySheet, def config, def pluginProjectName) {

        // Get configs property sheet
        def result = doHttpGet("/rest/v1.0/projects/$pluginProjectName/$configPropertySheet", /*failOnErrorCode*/ false)
        def configPropSheetId = result.data?.property?.propertySheetId
        if (!configPropSheetId) {
            handleProcedureError("No plugin configurations exist!")
        }

        result = doHttpGet("/rest/v1.0/propertySheets/$configPropSheetId", /*failOnErrorCode*/ false)
        // Get the property sheet id of the config from the result
        def configProp = result.data.propertySheet.property.find{
            it.propertyName == config
        }

        if (!configProp) {
            handleProcedureError("Configuration $config does not exist!")
        }

        result = doHttpGet("/rest/v1.0/propertySheets/$configProp.propertySheetId")

        def values = result.data.propertySheet.property.collectEntries{
            [(it.propertyName): it.value]
        }

        logger(INFO, "Plugin configuration values: " + values)

        def cred = getCredentials(config)
        values << [credential: [userName: cred.userName, password: cred.password]]

        //Set the log level using the plugin configuration setting
        logLevel = (values.logLevel?: INFO).toInteger()

        values
    }

    def getServiceCluster(String serviceName,
                          String projectName,
                          String applicationName,
                          String applicationRevisionId,
                          String environmentName,
                          String envProjectName) {

        def result = doHttpGet("/rest/v1.0/projects/${projectName}/applications/${applicationName}/tierMaps")

        logger DEBUG, "Tier Maps: " + JsonOutput.toJson(result)
        // Filter tierMap based on environment.
        def tierMap = result.data.tierMap.find {
            it.environmentName == environmentName && it.environmentProjectName == envProjectName
        }

        logger DEBUG, "Environment tier map for environment '$environmentName' and environment project '$envProjectName': \n" + JsonOutput.toJson(tierMap)
        // Filter applicationServiceMapping based on service name.
        def svcMapping = tierMap?.appServiceMappings?.applicationServiceMapping?.find {
            it.serviceName == serviceName
        }
        // If svcMapping not found, try with serviceClusterMappings for post 8.0 tierMap structure
        if (!svcMapping) {
            svcMapping = tierMap?.serviceClusterMappings?.serviceClusterMapping?.find {
                it.serviceName == serviceName
            }
        }

        // Fail if service mapping still not found fail.
        if (!svcMapping) {
            handleError("Could not find the service mapping for service '$serviceName', " +
                    "therefore, the cluster cannot be determined. Try specifying the cluster name " +
                    "explicitly when invoking 'Undeploy Service' procedure.")
        }
        logger DEBUG, "Service map for service '$serviceName': \n" + JsonOutput.toJson(svcMapping)
        svcMapping.clusterName

    }

    def getProvisionClusterParameters(String clusterName,
                                      String clusterOrEnvProjectName,
                                      String environmentName) {

        def partialUri = environmentName ?
                "projects/$clusterOrEnvProjectName/environments/$environmentName/clusters/$clusterName" :
                "projects/$clusterOrEnvProjectName/clusters/$clusterName"

        def result = doHttpGet("/rest/v1.0/$partialUri")

        def params = result.data.cluster?.provisionParameters?.parameterDetail

        if(!params) {
            handleError("No provision parameters found for cluster $clusterName!")
        }

        def provisionParams = params.collectEntries {
            [(it.parameterName): it.parameterValue]
        }

        logger DEBUG, "Cluster parameters from ElectricFlow cluster definition: $provisionParams"

        return provisionParams
    }

    def getServiceDeploymentDetails(String serviceName,
                                    String serviceProjectName,
                                    String applicationName,
                                    String applicationRevisionId,
                                    String clusterName,
                                    String clusterProjectName,
                                    String environmentName,
                                    String serviceEntityRevisionId = null) {

        def partialUri = applicationName ?
                "projects/$serviceProjectName/applications/$applicationName/services/$serviceName" :
                "projects/$serviceProjectName/services/$serviceName"
        def jobStepId = System.getenv('COMMANDER_JOBSTEPID')
        // def jobStepId = '$[/myJobStep/jobStepId]'
        def queryArgs = [
                request: 'getServiceDeploymentDetails',
                clusterName: clusterName,
                clusterProjectName: clusterProjectName,
                environmentName: environmentName,
                applicationEntityRevisionId: applicationRevisionId,
                jobStepId: jobStepId
        ]

        if (serviceEntityRevisionId) {
            queryArgs << [serviceEntityRevisionId: serviceEntityRevisionId]
        }

        def result = doHttpGet("/rest/v1.0/$partialUri", /*failOnErrorCode*/ true, queryArgs)

        def svcDetails = result.data.service
        logger DEBUG, "Service Details: " + JsonOutput.toJson(svcDetails)

        svcDetails
    }

    def expandString(String str) {
        def jobStepId = '$[/myJobStep/jobStepId]'
        def payload = [
                value: str,
                jobStepId: jobStepId
        ]

        def result = doHttpPost("/rest/v1.0/expandString", /* request body */ payload,
                /*failOnErrorCode*/ false, [request: 'expandString'])

        if (result.status >= 400){
            handleProcedureError("Failed to expand '$str'. $result.statusLine")
        }

        result.data?.value
    }

    def getActualParameters() {
        def jobId = '$[/myJob/jobId]'
        def result = doHttpGet("/rest/v1.0/jobs/$jobId")
        (result.data.job.actualParameter?:[:]).collectEntries {
            [(it.actualParameterName): it.value]
        }
    }

    def getCredentials(def credentialName) {
        def jobStepId = '$[/myJobStep/jobStepId]'
        // Use the new REST mapping for getFullCredential with 'credentialPaths'
        // which works around the restMapping matching issue with the credentialName being a path.
        def result = doHttpGet("/rest/v1.0/jobSteps/$jobStepId/credentialPaths/$credentialName")
        result.data.credential
    }

    def handleConfigurationError(String msg) {
        createProperty('/myJob/configError', msg)
        handleProcedureError(msg)
    }

    def handleProcedureError (String msg) {
        createProperty('summary', "ERROR: $msg")
        handleError(msg)
    }

    boolean runningInPipeline() {
        def result = getEFProperty('/myPipelineStageRuntime/id', /*ignoreError*/ true)
        return result.data ? true : false
    }

    def createProperty(String propertyName, String value, Map additionalArgs = [:]) {
        // Creating the property in the context of a job-step by default
        def jobStepId = '$[/myJobStep/jobStepId]'
        def payload = [:]
        payload << additionalArgs
        payload << [
                propertyName: propertyName,
                value: value,
                jobStepId: jobStepId
        ]

        doHttpPost("/rest/v1.0/properties", /* request body */ payload)
    }

    def createProperty2(String propertyName, String value, Map additionalArgs = [:]) {
        // Creating the property in the context of a job-step by default
        def jobStepId = '$[/myJobStep/jobStepId]'
        def payload = [:]
        payload << additionalArgs
        payload << [
                propertyName: propertyName,
                value: value,
                jobStepId: jobStepId
        ]
        // to prevent getting the value getting converted to json
        payload = JsonOutput.toJson(payload)
        doHttpPost("/rest/v1.0/properties", /* request body */ payload)
    }

    def createPropertyInPipelineContext(String applicationName,
                                        String serviceName, String targetPort,
                                        String propertyName, String value) {
        if (runningInPipeline()) {

            String relativeProp = applicationName ?
                    "${applicationName}/${serviceName}/${targetPort}" :
                    "${serviceName}/${targetPort}"
            String fullProperty = "/myStageRuntime/${relativeProp}/${propertyName}"
            logger INFO, "Registering pipeline runtime property '$fullProperty' with value $value"
            setEFProperty(fullProperty, value)
        }
    }

    def setEFProperty(String propertyName, String value, Map additionalArgs = [:]) {
        // Creating the property in the context of a job-step by default
        def jobStepId = '$[/myJobStep/jobStepId]'
        def payload = [:]
        payload << additionalArgs
        payload << [
                value: value,
                jobStepId: jobStepId
        ]
        // to prevent getting the value getting converted to json
        payload = JsonOutput.toJson(payload)
        doHttpPut("/rest/v1.0/properties/${propertyName}", /* request body */ payload)
    }


    def updateJobSummary(String message, boolean jobStepSummary = false) {
        updateSummary('/myJob/summary', message)
        if (jobStepSummary) {
            updateSummary('/myJobStep/summary', message)
        }
    }

    def updateSummary(String property, String message) {
        def summary = getEFProperty(property, true)?.value
        def lines = []
        if (summary) {
            lines = summary.split(/\n/)
        }
        lines.add(message)
        setEFProperty(property, lines.join("\n"))
    }

    def evalDsl(String dslStr) {
        // Run the dsl in the context of a job-step by default
        def jobStepId = '$[/myJobStep/jobStepId]'
        def payload = [:]
        payload << [
                dsl: dslStr,
                jobStepId: jobStepId
        ]

        doHttpPost("/rest/v1.0/server/dsl", /* request body */ payload)
    }

    def getEFProperty(String propertyName, boolean ignoreError = false) {
        // Get the property in the context of a job-step by default
        def jobStepId = '$[/myJobStep/jobStepId]'

        doHttpGet("/rest/v1.0/properties/${propertyName}",
                /* failOnErrorCode */ !ignoreError, [jobStepId: jobStepId])
    }

    // Discovery methods, EF model generation
    def createService(projName, payload, appName = null) {
        if (appName) {
            payload.applicationName = appName
        }
        def result = doRestPost("/rest/${REST_VERSION}/projects/${projName}/services", /* request body */ payload,
                /*failOnErrorCode*/ true)
        result?.data
    }

    def updateService(projName, serviceName, payload, applicationName = null) {
        if (applicationName) {
            payload.applicationName = applicationName
        }
        def result = doRestPut("/rest/${REST_VERSION}/projects/${projName}/services/${serviceName}", /* request body */ payload,
                /*failOnErrorCode*/ true)
        result?.data
    }

    def getServices(projName, appName = null) {
        def query = [:]
        if (appName) {
            query.applicationName = appName
        }
        def result = doHttpGet("/rest/${REST_VERSION}/projects/${projName}/services", true, query)
        result?.data?.service
    }

    def getPorts(projectName, serviceName, appName = null, containerName = null) {
        def query = [:]
        if (containerName) {
            query.containerName = containerName
        }
        if (appName) {
            query.applicationName = appName
        }
        def result = doHttpGet("/rest/${REST_VERSION}/projects/${projectName}/services/${serviceName}/ports", true, query)
        result?.data?.port
    }

    def createPort(projName, serviceName, payload, containerName = null, boolean failOnError = false, appName = null) {
        if (appName) {
            payload.applicationName = appName
        }
        if (containerName) {
            payload.containerName = containerName
        }
        payload.serviceName = serviceName
        def json = JsonOutput.toJson(payload)
        def result = doHttpPost("/rest/${REST_VERSION}/projects/${projName}/services/${serviceName}/ports", json, failOnError)
        result?.data
    }

    def createEnvironmentVariable(projName, serviceName, containerName, payload, failOnError = false, appName = null) {
        if (appName) {
            payload.applicationName = appName
        }
        payload.containerName = containerName
        payload.serviceName = serviceName
        def json = JsonOutput.toJson(payload)
        def result = doHttpPost("/rest/${REST_VERSION}/projects/${projName}/containers/${containerName}/environmentVariables", json, failOnError)
        result?.data
    }

    def getContainers(projectName, serviceName, applicationName = null) {
        def query = [
            serviceName: serviceName
        ]
        if (applicationName) {
            query.applicationName = applicationName
        }
        def result = doHttpGet("/rest/${REST_VERSION}/projects/${projectName}/containers", false, query)
        result?.data?.container
    }

    def updateContainer(String projectName, String serviceName, String containerName, payload, appName = null) {
        payload.serviceName = serviceName
        if (appName) {
            payload.applicationName = appName
        }
        def result = doRestPut("/rest/${REST_VERSION}/projects/${projectName}/containers/${containerName}", payload, true)
        result?.data
    }

    def createContainer(String projectName, String serviceName, payload, appName = null) {
        payload.serviceName = serviceName
        if (appName) {
            payload.applicationName = appName
        }
        def result = doRestPost("/rest/${REST_VERSION}/projects/${projectName}/containers", payload, true)
        result?.data
    }

    def getEnvMaps(projectName, serviceName) {
        def result = doHttpGet("/rest/${REST_VERSION}/projects/${projectName}/services/${serviceName}/environmentMaps")
        result?.data
    }

    def createEnvMap(projName, serviceName, payload) {
        def result = doRestPost("/rest/${REST_VERSION}/projects/${projName}/services/${serviceName}/environmentMaps", payload, true)
        result?.data
    }

    def createServiceClusterMapping(projName, serviceName, envMapName, payload, appName = null) {
        if (appName) {
            payload.applicationName = appName
        }
        def result = doRestPost("/rest/${REST_VERSION}/projects/${projName}/services/${serviceName}/environmentMaps/${envMapName}/serviceClusterMappings", payload, true)
        result?.data
    }

    def createServiceMapDetails(projName, serviceName, envMapName, serviceClusterMapName, payload, appName = null) {
        if (appName) {
            payload.applicationName = appName
        }
        def result = doRestPost("/rest/${REST_VERSION}/projects/${projName}/services/${serviceName}/environmentMaps/${envMapName}/serviceClusterMappings/${serviceClusterMapName}/serviceMapDetails", payload, false)
        result?.data
    }

    def createAppProcess(projName, applicationName, payload) {
        def result = doRestPost("/rest/${REST_VERSION}/projects/${projName}/applications/${applicationName}/processes", payload)
        result?.data
    }

    def createAppProcessStep(projName, applicationName, processName, payload) {
        def result = doRestPost("/rest/${REST_VERSION}/projects/${projName}/applications/${applicationName}/processes/${processName}/processSteps", payload, false)
        result?.data
    }

    def createProcess(projName, serviceName, payload, appName = null) {
        if (appName) {
            payload.applicationName = appName
        }
        def result = doRestPost("/rest/${REST_VERSION}/projects/${projName}/services/${serviceName}/processes", payload, false)
        result?.data
    }

    def createProcessStep(projName, serviceName, processName, payload, appName = null) {
        if (appName) {
            payload.applicationName = appName
        }
        def result = doRestPost("/rest/${REST_VERSION}/projects/${projName}/services/${serviceName}/processes/${processName}/processSteps", payload, false)
        result?.data
    }

    def getClusters(projName, envName) {
        def result = doHttpGet("/rest/${REST_VERSION}/projects/${projName}/environments/${envName}/clusters")
        result?.data?.cluster
    }

    def createCredential(projName, credName, userName, password) {
        def payload = [
            credentialName: credName,
            userName: userName,
            password: password
        ]
        def result = doRestPost("/rest/${REST_VERSION}/projects/${projName}/credentials", payload, false)
        result?.data?.credential
    }

    //rest application get
    def getApplications(projName){
        def result = doHttpGet("/rest/${REST_VERSION}/projects/${projName}/applications")
        result?.data?.application
    }

    //rest application post
    def createApplication(projName, appName){
        def payload = [
                applicationName: appName
        ]
        def result = doRestPost("/rest/${REST_VERSION}/projects/${projName}/applications", payload, false)
        result?.data?.application
    }

    //rest serviceClusterMapping get
    def getAppServiceClusterMapping(projName, appName, tierMapName) {
        def result = doHttpGet("/rest/${REST_VERSION}/projects/${projName}/applications/${appName}/tierMaps/${tierMapName}/serviceClusterMappings")
        result?.data?.serviceClusterMapping
    }

    //rest serviceClusterMapping post
    def createAppServiceClusterMapping(projName, appName, tierMapName, payload = null) {
        def result = doRestPost("/rest/${REST_VERSION}/projects/${projName}/applications/${appName}/tierMaps/${tierMapName}/serviceClusterMappings", payload, true)
        result?.data
    }

    //rest tierMap get
    def getTierMaps(projName, appName){
        def result = doHttpGet("/rest/${REST_VERSION}/projects/${projName}/applications/${appName}/tierMaps")
        result?.data?.tierMap
    }
    //rest tierMap post
    def createTierMap(projName, appName, payload){
        def result = doRestPost("/rest/${REST_VERSION}/projects/${projName}/applications/${appName}/tierMaps", payload, false)
        result?.data
    }
}