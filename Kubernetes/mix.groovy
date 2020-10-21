import groovy.transform.InheritConstructors
@InheritConstructors
class PluginException extends Exception {}

import static Logger.*

/**
 * Log levels supported by the plugin procedures
 */
class Logger {
    static final Integer DEBUG = 1
    static final Integer INFO = 2
    static final Integer WARNING = 3
    static final Integer ERROR = 4

    // Default log level used till the configured log level
    // is read from the plugin configuration.
    static Integer logLevel = INFO

    def static getLogLevelStr(Integer level) {
        switch (level) {
            case DEBUG:
                return '[DEBUG] '
            case INFO:
                return '[INFO] '
            case WARNING:
                return '[WARNING] '
            default://ERROR
                return '[ERROR] '

        }
    }
}

@Grab('org.codehaus.groovy.modules.http-builder:http-builder:0.7.1' )

import groovy.json.JsonBuilder
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovyx.net.http.HTTPBuilder
import groovyx.net.http.Method
import groovy.text.StreamingTemplateEngine

import static groovyx.net.http.ContentType.JSON
import static groovyx.net.http.Method.DELETE
import static groovyx.net.http.Method.GET
import static groovyx.net.http.Method.PATCH
import static groovyx.net.http.Method.POST
import static groovyx.net.http.Method.PUT

import static Logger.*

/**
 * Groovy client with common utility functions used in a plugin procedure
 * step such as for making HTTP requests, error handling, etc.
 */
public class BaseClient {

    Object doHttpRequest(Method method, String requestUrl,
                         String requestUri, def requestHeaders,
                         boolean failOnErrorCode = true,
                         Object requestBody = null,
                         def queryArgs = null) {

        logger DEBUG, "Request details:\n requestUrl: '$requestUrl' \n method: '$method' \n URI: '$requestUri'"
        if (queryArgs) {
            logger DEBUG, "queryArgs: '$queryArgs'"
        }
        logger DEBUG, "URL: '$requestUrl$requestUri'"
        if (requestBody) logger DEBUG, "Payload: $requestBody"

        def http = new HTTPBuilder(requestUrl)
        http.ignoreSSLIssues()

        http.request(method, JSON) {
            if (requestUri) {
                uri.path = requestUri
            }
            if (queryArgs) {
                uri.query = queryArgs
            }
            headers = requestHeaders
            body = requestBody

            response.success = { resp, json ->
                logger DEBUG, "request was successful $resp.statusLine.statusCode $json"
                [statusLine: resp.statusLine,
                 status: resp.status,
                 data : json]
            }

            if (failOnErrorCode) {
                response.failure = { resp, reader ->
                    logger ERROR, "Response: $reader"
                    handleError("Request failed with $resp.statusLine")
                }
            } else {
                response.failure = { resp, reader ->
                    logger DEBUG, "Response: $reader"
                    logger DEBUG, "Response: $resp.statusLine"
                    [statusLine: resp.statusLine,
                     status: resp.status]
                }
            }
        }
    }

    def mergeObjs(def dest, def src) {
        //Converting both object instances to a map structure
        //to ease merging the two data structures
        logger DEBUG, "Source to merge: " + JsonOutput.toJson(src)
        def result = mergeJSON((new JsonSlurper()).parseText((new JsonBuilder(dest)).toString()),
                (new JsonSlurper()).parseText((new JsonBuilder(src)).toString()))
        logger DEBUG, "After merge: " + JsonOutput.toJson(result)
        return result
    }

    def mergeJSON(def dest, def src) {
        src.each { prop, value ->
            logger DEBUG, "Has property $prop? value:" + dest[prop]
            if(dest[prop] != null && dest[prop] instanceof Map) {
                mergeJSON(dest[prop], value)
            } else {
                dest[prop] = value
            }
        }
        return dest
    }

    Object parseJsonToList(Object data){
        if(!data){
            return []
        }

        try {
            def parsed = new JsonSlurper().parseText(data)
            if (!(parsed instanceof List)) {
                parsed = [ parsed ]
            }
            return parsed
        } catch (Exception e) {
            e.printStackTrace();
            handleError ("Cannot parse json: $data")
        }
    }

    /**
     * Based on plugin parameter value truthiness
     * True if value == true or value == '1'
     */
    boolean toBoolean(def value) {
        return value != null && (value == true || value == 'true' || value == 1 || value == '1')
    }

    def handleError (String msg) {
        println "ERROR: $msg"
        System.exit(-1)
    }

    def logger(Integer level, def message) {
        if ( level >= logLevel ) {
            println getLogLevelStr(level) + message
        }
    }

    String formatName(String name){
        return name.replaceAll(' ', '-').replaceAll('_', '-').replaceAll("^-+", "").replaceAll("-+\$", "").toLowerCase()
    }

    def removeNullKeys(obj) {
      if(obj instanceof Map) {
        obj.collectEntries {k, v ->
          if(v) [(k): removeNullKeys(v)] else [:]
        }
      } else if(obj instanceof List) {
        obj.collect { removeNullKeys(it) }.findAll { it != null }
      } else {
        obj
      }
    }

    def applyTemplate(String textTemplate, def binding) {
        def template = new StreamingTemplateEngine().createTemplate(textTemplate)
        template.make(binding).toString()
    }

    //Flag for use use during development if there is no internet access
    //in which case remote calls will become no-ops.
    //Should *never* be checked in with a value of true.
    final boolean OFFLINE = false

}
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
        // def jobStepId = 'cedc77d6-f7f3-11ea-8e77-0242ac170008'
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
        def jobStepId = 'cedc77d6-f7f3-11ea-8e77-0242ac170008'
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
        def jobId = 'ceb5b5ce-f7f3-11ea-95d5-0242ac170008'
        def result = doHttpGet("/rest/v1.0/jobs/$jobId")
        (result.data.job.actualParameter?:[:]).collectEntries {
            [(it.actualParameterName): it.value]
        }
    }

    def getCredentials(def credentialName) {
        def jobStepId = 'cedc77d6-f7f3-11ea-8e77-0242ac170008'
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
        def jobStepId = 'cedc77d6-f7f3-11ea-8e77-0242ac170008'
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
        def jobStepId = 'cedc77d6-f7f3-11ea-8e77-0242ac170008'
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
        def jobStepId = 'cedc77d6-f7f3-11ea-8e77-0242ac170008'
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
        def jobStepId = 'cedc77d6-f7f3-11ea-8e77-0242ac170008'
        def payload = [:]
        payload << [
                dsl: dslStr,
                jobStepId: jobStepId
        ]

        doHttpPost("/rest/v1.0/server/dsl", /* request body */ payload)
    }

    def getEFProperty(String propertyName, boolean ignoreError = false) {
        // Get the property in the context of a job-step by default
        def jobStepId = 'cedc77d6-f7f3-11ea-8e77-0242ac170008'

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



import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import static groovyx.net.http.ContentType.JSON
import static groovyx.net.http.Method.DELETE
import static groovyx.net.http.Method.GET
import static groovyx.net.http.Method.PATCH
import static groovyx.net.http.Method.POST
import static groovyx.net.http.Method.PUT
@Grab('com.jayway.jsonpath:json-path:2.0.0' )

import static com.jayway.jsonpath.JsonPath.parse

/**
 * Kubernetes API client
 */
public class KubernetesClient extends BaseClient {

    String kubernetesVersion = '1.6'

    String retrieveAccessToken(def pluginConfig) {
        "Bearer ${pluginConfig.credential.password}"
    }

    /**
    * A way to check cluster is up/reachable. Currently simply hits API base endpoint
    * In future there might be a "health" endpoint available which can be used
    */
    def checkClusterHealth(String clusterEndPoint, String accessToken){

        if (OFFLINE) return null

        doHttpGet(clusterEndPoint,
                "/apis",
                accessToken, /*failOnErrorCode*/ false)
    }

    def deployService(
            EFClient efClient,
            String accessToken,
            String clusterEndpoint,
            String namespace,
            String serviceName,
            String serviceProjectName,
            String applicationName,
            String applicationRevisionId,
            String clusterName,
            String clusterOrEnvProjectName,
            String environmentName,
            String resultsPropertySheet,
            String serviceEntityRevisionId = null) {

        def serviceDetails = efClient.getServiceDeploymentDetails(
                serviceName,
                serviceProjectName,
                applicationName,
                applicationRevisionId,
                clusterName,
                clusterOrEnvProjectName,
                environmentName,
                serviceEntityRevisionId)

        validateUniquePorts(serviceDetails)

        createOrCheckNamespace(clusterEndpoint, namespace, accessToken)

        createOrUpdateService(clusterEndpoint, namespace, serviceDetails, accessToken)

        createOrUpdateDeployment(clusterEndpoint, namespace, serviceDetails, accessToken)

        createOrUpdateResourceIfNeeded(clusterEndpoint, serviceDetails, accessToken)

        // hook for other kubernetes based plugins
        createOrUpdatePlatformSpecificResources(clusterEndpoint, namespace, serviceDetails, accessToken)

        // lookup certain key service parameters first
        boolean isCanary = isCanaryDeployment(serviceDetails)
        def expectedReplicaCount = getExpectedReplicaCount(serviceDetails, isCanary)

        waitForDeployment(efClient, clusterEndpoint, namespace, serviceDetails, expectedReplicaCount, isCanary, accessToken)

        def serviceType = getServiceParameter(serviceDetails, 'serviceType', 'LoadBalancer')
        boolean serviceCreatedOrUpdated = !isCanary
        if (!serviceCreatedOrUpdated) {
            return
        }

        if(serviceDetails.port){

            // If port mappings are defined then get Endpoint details
            // else headless service without any Endpoint IP is created
            switch (serviceType) {
            case 'LoadBalancer':
                def serviceEndpoint = getLBServiceEndpoint(clusterEndpoint, namespace, serviceDetails, accessToken)

                if (serviceEndpoint) {
                    serviceDetails.port?.each { port ->
                        def targetPort = port.subport?:port.listenerPort
                        String url = "${serviceEndpoint}:${port.listenerPort}"
                        logger INFO, "Load Balancer Endpoint: ${url}"
                        efClient.createProperty("${resultsPropertySheet}/${serviceName}/${targetPort}/url", url)
                        efClient.createPropertyInPipelineContext(applicationName, serviceName, targetPort, 'url', url)
                    }
                }
                break

            case 'NodePort':
                def clusterIP = getClusterIPServiceEndpoint(clusterEndpoint, namespace, serviceDetails, accessToken)
                serviceDetails.port?.each { port ->
                    String portName = port.portName
                    def targetPort = port.subport?:port.listenerPort
                    String url = "${clusterIP}:${port.listenerPort}"
                    efClient.createProperty("${resultsPropertySheet}/${serviceName}/${targetPort}/url", url)
                    efClient.createPropertyInPipelineContext(applicationName, serviceName, targetPort, 'url', url)
                    logger INFO, "NodePort endpoint: ${url}"
                    // get the assigned node port for the service port
                    def nodePort = getNodePortServiceEndpoint(clusterEndpoint, namespace, serviceDetails, portName, accessToken)
                    if (nodePort) {
                        logger INFO, "Node Port: $nodePort"
                        efClient.createProperty("${resultsPropertySheet}/${serviceName}/${targetPort}/nodePort", "$nodePort")
                        efClient.createPropertyInPipelineContext(applicationName, serviceName, targetPort, 'nodePort', "$nodePort")
                    } else {
                        logger WARNING, "Nodeport not found for deployed service '$serviceName' for port '$portName'"
                    }
                }
                break

            default: //for both 'NodePort' and 'ClusterIP', service is accessible through ClusterIP:port
                def clusterIP = getClusterIPServiceEndpoint(clusterEndpoint, namespace, serviceDetails, accessToken)
                serviceDetails.port?.each { port ->
                    def targetPort = port.subport?:port.listenerPort
                    String url = "${clusterIP}:${port.listenerPort}"
                    logger INFO, "Endpoint: ${url}"
                    efClient.createProperty("${resultsPropertySheet}/${serviceName}/${targetPort}/url", url)
                    efClient.createPropertyInPipelineContext(applicationName, serviceName, targetPort, 'url', url)
                }
                break

            }
        }else{
            // Headless service
            efClient.createProperty("${resultsPropertySheet}/${serviceName}/none/url", "none")
            efClient.createPropertyInPipelineContext(applicationName, serviceName, "none", 'url', "none")
        }

    }

    def getExpectedReplicaCount(def args, boolean isCanary) {
        isCanary ? getServiceParameter(args, 'numberOfCanaryReplicas', 1).toInteger() : args.defaultCapacity.toInteger()
    }

    def waitForDeployment(EFClient efClient, def clusterEndpoint, def namespace, def serviceDetails,
                          def expectedReplicaCount, boolean isCanary, def accessToken){

        String apiPath = versionSpecificAPIPath('deployments')
        def deploymentTimeoutInSec = getServiceParameter(serviceDetails, 'deploymentTimeoutInSec', 120).toInteger()
        def deploymentName = getDeploymentName(serviceDetails)
        String resourceUri = "/apis/${apiPath}/namespaces/${namespace}/deployments/$deploymentName/status"

        def response
        int elapsedTime = 0
        int iteration = 0
        while(elapsedTime < deploymentTimeoutInSec) {

            if (iteration > 0) {
                if (iteration == 1) {
                    logger INFO, "Waiting for deployment to complete ..."
                }
                if (iteration > 1) {
                    logger INFO, "ElapsedTime: $elapsedTime seconds"
                }

                 def before = System.currentTimeMillis()
                 Thread.sleep(10*1000)
                 def now = System.currentTimeMillis()

                 elapsedTime = elapsedTime + (now - before)/1000
            }
            iteration++

            response = doHttpGet(clusterEndpoint,
                resourceUri,
                accessToken, /*failOnErrorCode*/ false)
            if(deploymentStatusComplete(response, expectedReplicaCount)){
                logger INFO, "Deployment completed"
                return
            }
        }

        //If reached here, then deployment did not complete within the specified time.
        if (response) {
            def responseStr = (new JsonBuilder(response)).toPrettyString()
            logger INFO, "Deployment status:\n$responseStr"
            def serviceName = getServiceNameToUseForDeployment(serviceDetails)
            def selectorLabel = getSelectorLabelForDeployment(serviceDetails, serviceName, isCanary)
            logDeploymentPodsStatus(clusterEndpoint, namespace, selectorLabel, deploymentName, isCanary, accessToken)
        }
        efClient.handleProcedureError("Deployment did not complete within ${deploymentTimeoutInSec} seconds.")
    }

    def logDeploymentPodsStatus(String clusterEndpoint, String namespace, String selectorLabel,
                                String deploymentName, boolean isCanary, String accessToken) {

        def deploymentFlag = isCanary ? 'canary' : 'stable'

        def response = doHttpGet(clusterEndpoint,
                "/api/v1/namespaces/${namespace}/pods",
                accessToken, /*failOnErrorCode*/ false,
                [labelSelector: "ec-track=${deploymentFlag},ec-svc=${selectorLabel}"])

        logger INFO, "Pod(s) associated with deployment '$deploymentName':"
        for( pod in response?.data?.items ){

            def podStr = (new JsonBuilder(pod)).toPrettyString()
            logger INFO, "Pod '${pod.metadata.name}':\n$podStr"
        }
    }

    def deploymentStatusComplete(def response, def expectedReplicaCount){
        for (condition in response?.data?.status?.conditions){
            if(condition.reason == "NewReplicaSetAvailable" && condition.status == "True" ){
                return true
            }
        }
        //check for older kubernetes deployment API
        def availableReplicas = response?.data?.status?.availableReplicas
        return availableReplicas && expectedReplicaCount == availableReplicas
    }

    def undeployService(
            EFClient efClient,
            String accessToken,
            String clusterEndpoint,
            String namespace,
            String serviceName,
            String serviceProjectName,
            String applicationName,
            String applicationRevisionId,
            String clusterName,
            String clusterOrEnvProjectName,
            String environmentName,
            String serviceEntityRevisionId = null) {

        def serviceDetails = efClient.getServiceDeploymentDetails(
                serviceName,
                serviceProjectName,
                applicationName,
                applicationRevisionId,
                clusterName,
                clusterOrEnvProjectName,
                environmentName,
                serviceEntityRevisionId)

        if (!isCanaryDeployment(serviceDetails)) {
            deleteService(clusterEndpoint, namespace, serviceDetails, accessToken)

            // Explicitly delete replica sets. Currently can not delete Deployment with cascade option
            // since sending '{"kind":"DeleteOptions","apiVersion":"v1","propagationPolicy":"Foreground"}'
            // as body to DELETE HTTP request is not allowed by HTTPBuilder
            deleteDeployment(clusterEndpoint, namespace, serviceDetails, accessToken)

            deleteReplicaSets(clusterEndpoint, namespace, serviceDetails, accessToken)

            deletePods(clusterEndpoint, namespace, serviceDetails, accessToken)
        }

        def canaryDeploymentName = constructCanaryDeploymentName(serviceDetails)
        // Explicitly delete replica sets. Currently can not delete Deployment with cascade option
        // since sending '{"kind":"DeleteOptions","apiVersion":"v1","propagationPolicy":"Foreground"}'
        // as body to DELETE HTTP request is not allowed by HTTPBuilder
        deleteDeployment(clusterEndpoint, namespace, serviceDetails, accessToken, canaryDeploymentName)

        deleteReplicaSets(clusterEndpoint, namespace, serviceDetails, accessToken, canaryDeploymentName)

        deletePods(clusterEndpoint, namespace, serviceDetails, accessToken, canaryDeploymentName)

    }

    def getPluginConfig(EFClient efClient, String clusterName, String clusterOrEnvProjectName, String environmentName) {

        def clusterParameters = efClient.getProvisionClusterParameters(
                clusterName,
                clusterOrEnvProjectName,
                environmentName)

        def configName = clusterParameters.config
        def pluginProjectName = 'EC-OpenShift-1.6.2.2020072702'
        def pluginConfig = efClient.getConfigValues('ec_plugin_cfgs', configName, pluginProjectName)
        this.setVersion(pluginConfig)
        pluginConfig
    }

    def createOrCheckNamespace(String clusterEndPoint, String namespace, String accessToken){

        if (OFFLINE) return null

        def response = doHttpGet(clusterEndPoint,
                "/api/v1/namespaces/${namespace}",
                accessToken, /*failOnErrorCode*/ false)
        if (response.status == 200){
            logger INFO, "Namespace ${namespace} already exists"
            return
        }
        else if (response.status == 404){
            def namespaceDefinition = buildNamespacePayload(namespace)
            logger INFO, "Creating Namespace ${namespace}"
            response = doHttpRequest(POST,
                    clusterEndPoint,
                    '/api/v1/namespaces',
                    ['Authorization' : accessToken],
                    /*failOnErrorCode*/ false,
                    namespaceDefinition)

            // Ignore if the namespace got created and
            // we get a 409 conflict for namespace already exists,
            // otherwise report the error
            if (response.status >= 400 && response.status != 409) {
                handleError("Request failed with $response.statusLine")
            }
        }
        else {
            handleError("Namespace check failed. ${response.statusLine}")
        }
    }

    String buildNamespacePayload(String namespace){
        def json = new JsonBuilder()
        def result = json{
            kind "Namespace"
            apiVersion "v1"
            metadata {
                name namespace
            }
        }
        return (new JsonBuilder(result)).toPrettyString()
    }

    /**
     * Retrieves the Deployment instance from Kubernetes cluster.
     * Returns null if no Deployment instance by the given name is found.
     */
    def getDeployment(String clusterEndPoint, String namespace, String deploymentName, String accessToken) {

        if (OFFLINE) return null

        String apiPath = versionSpecificAPIPath('deployments')
        def response = doHttpGet(clusterEndPoint,
                "/apis/${apiPath}/namespaces/${namespace}/deployments/${formatName(deploymentName)}",
                accessToken, /*failOnErrorCode*/ false)
        response.status == 200 ? response.data : null
    }

    def getDeployments(String clusterEndPoint, String namespace, String accessToken, parameters = [:]) {

        if (OFFLINE) return null

        def query = [:]
        if (parameters.labelSelector) {
            query.labelSelector = parameters.labelSelector
        }
        String apiPath = versionSpecificAPIPath('deployments')
        def response = doHttpGet(clusterEndPoint,
                "/apis/${apiPath}/namespaces/${namespace}/deployments",
                accessToken, /*failOnErrorCode*/ false, null, query)

        def str = response.data ? (new JsonBuilder(response.data)).toPrettyString(): response.data
        logger DEBUG, "Deployments found: $str"
        response.status == 200 ? response.data : null
    }


    def getPods(String clusterEndPoint, String namespace, String accessToken, Map parameters = [:]) {

        if (OFFLINE) return null

        def query = [:]
        if (parameters.labelSelector) {
            query.labelSelector = parameters.labelSelector
        }

        def response = doHttpGet(clusterEndPoint, "/api/v1/namespaces/${namespace}/pods", accessToken, /*failOnErrorCode*/ false, null, query)

        def str = response.data ? (new JsonBuilder(response.data)).toPrettyString(): response.data
        logger DEBUG, "Pods found: $str"
        response.status == 200 ? response.data : null
    }

    def getNodes(String clusterEndPoint, String accessToken) {
        if (OFFLINE) {
            return null
        }
        def response = doHttpGet(clusterEndPoint, "/api/v1/nodes", accessToken, true, null, null)
        def str = response.data ? (new JsonBuilder(response.data)).toPrettyString() : response.data
        response.status == 200 ? response.data : null
    }


    def getVersion(String clusterEndPoint, String accessToken) {
        if (OFFLINE) {
            return null
        }
        def response = doHttpGet(clusterEndPoint, "/version", accessToken, true, null, null)
        response.status == 200 ? response.data : null
    }

    /**
     * Retrieves the Service instance from Kubernetes cluster.
     * Returns null if no Service instance by the given name is found.
     */
    def getService(String clusterEndPoint, String namespace, String serviceName, String accessToken) {

        if (OFFLINE) return null

        def response = doHttpGet(clusterEndPoint,
                "/api/v1/namespaces/${namespace}/services/$serviceName",
                accessToken, /*failOnErrorCode*/ false)

        if (response.data) {
            def payload = response.data
            String str = (new JsonBuilder(payload)).toPrettyString()
            logger INFO, "Deployed service:\n $str"
        }

        response.status == 200 ? response.data : null
    }


    def getServices(String clusterEndPoint, String namespace, String accessToken) {

        if (OFFLINE) return null

        def response = doHttpGet(clusterEndPoint,
                "/api/v1/namespaces/${namespace}/services",
                accessToken, /*failOnErrorCode*/ false)
        def str = response.data ? (new JsonBuilder(response.data)).toPrettyString(): response.data
        logger DEBUG, "Services found: $str"
        response.status == 200 ? response.data : null
    }

    def createOrUpdateService(String clusterEndPoint, String namespace , def serviceDetails, String accessToken) {

        String serviceName = getServiceNameToUseForDeployment(serviceDetails)
        def deployedService = getService(clusterEndPoint, namespace, serviceName, accessToken)

        def serviceDefinition = buildServicePayload(serviceDetails, deployedService)

        if (OFFLINE) return null

        if(serviceDefinition!=null){
            if(deployedService){
                logger INFO, "Updating deployed service $serviceName"
                doHttpRequest(PUT,
                        clusterEndPoint,
                        "/api/v1/namespaces/$namespace/services/$serviceName",
                        ['Authorization' : accessToken],
                        /*failOnErrorCode*/ true,
                        serviceDefinition)

            } else {
                logger INFO, "Creating service $serviceName"
                doHttpRequest(POST,
                        clusterEndPoint,
                        "/api/v1/namespaces/${namespace}/services",
                        ['Authorization' : accessToken],
                        /*failOnErrorCode*/ true,
                        serviceDefinition)
            }
        }
    }

    def deleteService(String clusterEndPoint, String namespace , def serviceDetails, String accessToken) {

        String serviceName = getServiceNameToUseForDeployment(serviceDetails)

        def deployedService = getService(clusterEndPoint, namespace, serviceName, accessToken)

        if (OFFLINE) return null

        if(deployedService){
            logger INFO, "Deleting service $serviceName"
            doHttpRequest(DELETE,
                    clusterEndPoint,
                    "/api/v1/namespaces/$namespace/services/$serviceName",
                    ['Authorization' : accessToken],
                    /*failOnErrorCode*/ true)
        }else{
            logger INFO, "Service $serviceName does not exist"
        }
    }

    def getLBServiceEndpoint(String clusterEndPoint, String namespace, def serviceDetails, String accessToken) {

        def lbEndpoint
        def elapsedTime = 0;
        def timeInSeconds = 5*60
        String serviceName = getServiceNameToUseForDeployment(serviceDetails)
        while (elapsedTime <= timeInSeconds) {
            def before = System.currentTimeMillis()
            Thread.sleep(10*1000)

            def deployedService = getService(clusterEndPoint, namespace, serviceName, accessToken)
            def lbIngress = deployedService?.status?.loadBalancer?.ingress.find {
                it.ip != null || it.hostname != null
            }

            if (lbIngress) {
                lbEndpoint = lbIngress.ip?:lbIngress.hostname
                break
            }
            logger INFO, "Waiting for service status to publish loadbalancer ingress... \nElapsedTime: $elapsedTime seconds"

            def now = System.currentTimeMillis()
            elapsedTime = elapsedTime + (now - before)/1000
        }

        if (!lbEndpoint) {
            logger INFO, "Loadbalancer ingress not published yet. Defaulting to specified loadbalancer IP."
            def value = getServiceParameter(serviceDetails, 'loadBalancerIP')
            lbEndpoint = value
        }
        lbEndpoint
    }

    def getClusterIPServiceEndpoint(String clusterEndPoint, String namespace, def serviceDetails, String accessToken) {

        String serviceName = getServiceNameToUseForDeployment(serviceDetails)
        def deployedService = getService(clusterEndPoint, namespace, serviceName, accessToken)
        //get the clusterIP for the service
        deployedService?.spec?.clusterIP
    }

    def getNodePortServiceEndpoint(String clusterEndPoint, String namespace, def serviceDetails, String portName, String accessToken) {

        String serviceName = getServiceNameToUseForDeployment(serviceDetails)
        def deployedService = getService(clusterEndPoint, namespace, serviceName, accessToken)
        //get the published node port for the specified portName
        def servicePort = deployedService?.spec?.ports?.find {
            it.name = portName
        }
        servicePort?.nodePort
    }

    def createOrUpdateSecret(def secretName, def username, def password, def repoBaseUrl,
                         String clusterEndPoint, String namespace, String accessToken){
        def existingSecret = getSecret(secretName, clusterEndPoint, namespace, accessToken)
        def secret = buildSecretPayload(secretName, username, password, repoBaseUrl)
        if (OFFLINE) return null
        if (existingSecret) {
                    logger INFO, "Updating existing Secret $secretName"
                    doHttpRequest(PUT,
                            clusterEndPoint,
                            "/api/v1/namespaces/${namespace}/secrets/${secretName}",
                            ['Authorization' : accessToken],
                            /*failOnErrorCode*/ true,
                            secret)

                } else {
                    logger INFO, "Creating Secret $secretName"
                    doHttpRequest(POST,
                            clusterEndPoint,
                            "/api/v1/namespaces/${namespace}/secrets",
                            ['Authorization' : accessToken],
                            /*failOnErrorCode*/ true,
                            secret)
                }
    }

    def getSecret(def secretName, def clusterEndPoint, String namespace, def accessToken) {

        if (OFFLINE) return null

        def response = doHttpGet(clusterEndPoint,
                "/api/v1/namespaces/${namespace}/secrets/${secretName}",
                accessToken, /*failOnErrorCode*/ false)
        response.status == 200 ? response.data : null
    }

    def buildSecretPayload(def secretName, def username, def password, def repoBaseUrl){
        def encodedCreds = (username+":"+password).bytes.encodeBase64().toString()
        def dockerCfgData = ["${repoBaseUrl}": [ username: username,
                                                password: password,
                                                email: "none",
                                                auth: encodedCreds]
                            ]
        def dockerCfgJson = new JsonBuilder(dockerCfgData)
        def dockerCfgEnoded = dockerCfgJson.toString().bytes.encodeBase64().toString()
        def secret = [ apiVersion: "v1",
                       kind: "Secret",
                       metadata: [name: secretName],
                       data: [".dockercfg": dockerCfgEnoded],
                       type: "kubernetes.io/dockercfg"]

        def secretJson = new JsonBuilder(secret)
        return secretJson.toPrettyString()
    }

    def constructSecretName(String imageUrl, String username){
        def imageDetails = imageUrl.tokenize('/')
        if (imageDetails.size() < 2) {
            handleError("Please check that the registry url was specified for the image.")
        }
        String repoBaseUrl = imageDetails[0]
        def secretName = repoBaseUrl + "-" + username
        // To comply with DNS-1123 standard for secret names
        // 1. Replace any non-alphanumeric characters with '-'
        // 2. Prepend and append character 's', if secret name start and end with non-alphanumeric character
        // 3. Convert all characters to lower case
        return [repoBaseUrl, secretName.replaceAll(/[^a-zA-Z\d\.-]/, '-').replaceAll(/^[^a-zA-Z\d]/, 's').replaceAll(/[^a-zA-Z\d]$/, 's').toLowerCase()]
    }

    def createOrUpdateDeployment(String clusterEndPoint, String namespace, def serviceDetails, String accessToken) {

        // Use the same name as the service name to create a Deployment in Kubernetes
        // that will drive the deployment of the service pods.
        def imagePullSecrets = []
        serviceDetails.container.collect { svcContainer ->
            //Prepend the registry to the imageName
            //if it does not already include it.
            if (svcContainer.registryUri) {
                String image = svcContainer.imageName
                if (!image.startsWith("${svcContainer.registryUri}/")) {
                    svcContainer.imageName = "${svcContainer.registryUri}/$image"
                }
            }

            if(svcContainer.credentialName){

                EFClient efClient = new EFClient()
                def cred = efClient.getCredentials(svcContainer.credentialName)
                def (repoBaseUrl, secretName) = constructSecretName(svcContainer.imageName, cred.userName)
                createOrUpdateSecret(secretName, cred.userName, cred.password, repoBaseUrl,
                        clusterEndPoint, namespace, accessToken)
                if (!imagePullSecrets.contains(secretName)) {
                    imagePullSecrets.add(secretName)
                }
            }
        }

        def deploymentName = getDeploymentName(serviceDetails)
        def existingDeployment = getDeployment(clusterEndPoint, namespace, deploymentName, accessToken)
        def deployment = buildDeploymentPayload(serviceDetails, existingDeployment, imagePullSecrets)
        logger DEBUG, "Deployment payload:\n $deployment"

        if (OFFLINE) return null

        String apiPath = versionSpecificAPIPath('deployments')
        if (existingDeployment) {
            logger INFO, "Updating existing deployment $deploymentName"
            doHttpRequest(PUT,
                    clusterEndPoint,
                    "/apis/${apiPath}/namespaces/${namespace}/deployments/$deploymentName",
                    ['Authorization' : accessToken],
                    /*failOnErrorCode*/ true,
                    deployment)

        } else {
            logger INFO, "Creating deployment $deploymentName"
            doHttpRequest(POST,
                    clusterEndPoint,
                    "/apis/${apiPath}/namespaces/${namespace}/deployments",
                    ['Authorization' : accessToken],
                    /*failOnErrorCode*/ true,
                    deployment)
        }

    }

    def deleteReplicaSets(String clusterEndPoint, String namespace, def serviceDetails, String accessToken, String deploymentName = null){

        if (OFFLINE) return null
        deploymentName = deploymentName?:getDeploymentName(serviceDetails)

        def response = doHttpGet(clusterEndPoint,
                "/apis/extensions/v1beta1/namespaces/${namespace}/replicasets",
                accessToken, /*failOnErrorCode*/ false)

        for(replSet in response.data.items){

            // Name of replicaSet follow the convension as <deployment_name>-uniqueId
            // Filter out replicaSets which contains Deployment name in their names.
            def matcher = replSet.metadata.name =~ /(.*)-([^-]+)$/
            try{

                def replName = matcher[0][1]
                if(replName == deploymentName){

                    def resp = doHttpRequest(DELETE, clusterEndPoint,
                        "/apis/extensions/v1beta1/namespaces/${namespace}/replicasets/${replSet.metadata.name}",
                        ['Authorization' : accessToken],
                        /*failOnErrorCode*/ false)
                    logger INFO, "Deleting replicaSet ${replSet.metadata.name}. Response : ${resp}"
                }
            }catch(IndexOutOfBoundsException e){
                // ReplicaSet does not contain deployment name
                // Continue to next ReplicaSet
            }
        }
     }

    def deletePods(String clusterEndPoint, String namespace, def serviceDetails, String accessToken, String deploymentName = null){

        if (OFFLINE) return null
        deploymentName = deploymentName?:getDeploymentName(serviceDetails)

        def response = doHttpGet(clusterEndPoint,
                "/api/v1/namespaces/${namespace}/pods",
                accessToken, /*failOnErrorCode*/ false)

        for( pod in response.data.items ){

            // Name of replicaSet follow the convension as <deployment_name>-replicaSetUniqueId-podUniqueId
            // Filter out pods which contains Deployment name in their names.
            def matcher = pod.metadata.name =~ /(.*)-([^-]+)-([^-]+)$/
            try{

                def podName = matcher[0][1]
                if(podName == deploymentName){

                    def resp = doHttpRequest(DELETE, clusterEndPoint,
                        "/api/v1/namespaces/${namespace}/pods/${pod.metadata.name}",
                        ['Authorization' : accessToken],
                        /*failOnErrorCode*/ false)
                    logger INFO, "Deleting pod ${pod.metadata.name}. Response : ${resp}"
                }
            }catch(IndexOutOfBoundsException e){
                // ReplicaSet does not contain deployment name
                // Continue to next ReplicaSet
            }
        }
    }

    def deleteDeployment(String clusterEndPoint, String namespace, def serviceDetails, String accessToken, String deploymentName = null) {

        deploymentName = deploymentName?:getDeploymentName(serviceDetails)
        def existingDeployment = getDeployment(clusterEndPoint, namespace, deploymentName, accessToken)

        if (OFFLINE) return null

        String apiPath = versionSpecificAPIPath('deployments')
        if (existingDeployment) {
            logger INFO, "Deleting deployment $deploymentName"
            doHttpRequest(DELETE,
                    clusterEndPoint,
                    "/apis/${apiPath}/namespaces/${namespace}/deployments/$deploymentName",
                    ['Authorization' : accessToken],
                    /*failOnErrorCode*/ true)

        } else {
            logger INFO, "Deployment $deploymentName does not exist"
        }

    }

    def createOrUpdatePlatformSpecificResources(String clusterEndPoint, String namespace, def serviceDetails, String accessToken) {
        // no-op. Hook for other plugins based on EC-Kubernetes
    }

    def createOrUpdateResourceIfNeeded(String clusterEndPoint, def serviceDetails, String accessToken) {
        boolean addOrUpdateRsrc = toBoolean(getServiceParameter(serviceDetails, 'createOrUpdateResource'))
        if (addOrUpdateRsrc) {
            String resourceUri = getServiceParameter(serviceDetails, 'resourceUri')
            def resourcePayload = getServiceParameter(serviceDetails, 'resourceData')
            if (resourceUri && resourcePayload) {
                String contentType = determineContentType(resourcePayload)
                String createOrUpdate = getServiceParameter(serviceDetails, 'requestType', 'create')
                createOrUpdateResource(clusterEndPoint, resourcePayload, resourceUri, createOrUpdate, contentType, accessToken)
            } else {
                resourceUri ?
                        handleError("Additional resource payload not provided for creating or updating additional Kubernetes resource.") :
                        handleError("Additional resource URI not provided for creating or updating additional Kubernetes resource.")
            }
        }

    }

    String determineContentType(def payload) {
        try {
            new JsonSlurper().parseText(payload)
            'application/json'
        } catch (Exception ex) {
            // Default to yaml and let the kubernetes API deal with it.
            // Don't want to introduce a dependency on another jar
            // just to check the format here.
            'application/yaml'
        }
    }

    def createOrUpdateResource(String clusterEndPoint, def resourceDetails, String resourceUri, String createFlag, String contentType, String accessToken) {

        if (OFFLINE) return null

        switch (createFlag) {
            case 'create':
                logger INFO, "Creating resource at ${resourceUri}"
                return doHttpRequest(POST,
                        clusterEndPoint,
                        resourceUri,
                        ['Authorization' : accessToken, 'Content-Type': contentType],
                        /*failOnErrorCode*/ true,
                        resourceDetails)
            case 'update':
                logger INFO, "Updating resource at ${resourceUri}"
                return doHttpRequest(PUT,
                        clusterEndPoint,
                        resourceUri,
                        ['Authorization' : accessToken, 'Content-Type': contentType],
                        /*failOnErrorCode*/ true,
                        resourceDetails)
            case 'patch':
                //explicitly set the contentType for patch to the support type
                contentType = 'application/strategic-merge-patch+json'
                logger INFO, "Patching resource at ${resourceUri}"
                return doHttpRequest(PATCH,
                        clusterEndPoint,
                        resourceUri,
                        ['Authorization' : accessToken, 'Content-Type': contentType],
                        /*failOnErrorCode*/ true,
                        resourceDetails)
        }
    }

    def waitKubeAPI(String clusterEndpoint,
                    def resourceData,
                    String resourceUri,
                    String requestType,
                    String requestFormat,
                    String accessToken,
                    String responseField,
                    String expectedValue,
                    int timeoutInSec) {

        if (OFFLINE) return null

        logger INFO, "Checking '$responseField' of ${clusterEndpoint}/${resourceUri}"

        int elapsedTime = 0
        int iteration = 0
        while(elapsedTime < timeoutInSec) {

            def response = invokeKubeAPI(clusterEndpoint, resourceData, resourceUri, requestType, requestFormat, accessToken)

            def responseValue = parse(response.data).read('$.' + responseField)

            logger DEBUG, "Got value of '${responseField}' : '${responseValue}'"

            if(responseValue==expectedValue){
                logger INFO, "Matched : ${responseValue} with ${expectedValue}"
                return
            }
            def before = System.currentTimeMillis()
            Thread.sleep(10*1000)
            def now = System.currentTimeMillis()

            elapsedTime = elapsedTime + (now - before)/1000
        }
        handleError("Timed out waiting for value of '${responseField}' response field ('${clusterEndpoint}/${resourceUri}') to attain '${expectedValue}'.")
    }

    def invokeKubeAPI(String clusterEndPoint, def resourceDetails, String resourceUri, String requestType, String requestFormat, String accessToken) {

        if (OFFLINE) return null
        logger DEBUG, "Invoking API with clusterEndPoint: $clusterEndPoint, resourceUri: $resourceUri, requestType: $requestType, requestFormat: $requestFormat, payload: '$resourceDetails'."

        def method = Method.valueOf(requestType)?:GET

        if (method == GET || method == DELETE) {
            if (resourceDetails) {
                handleError("Request payload cannot be specified for HTTP method '$requestType'. Clear or unset the request payload parameter and retry.")
            } else {
                resourceDetails = null
            }
        }

        def contentType = requestFormat == 'yaml' ? 'application/yaml' : 'application/json'
        //set special content type for PATCH
        if (method == PATCH) {
            if (requestFormat == 'yaml') {
                handleError("Request format 'yaml' not supported for HTTP method '$requestType'. Use request format 'json' and retry.")
            }
            contentType = 'application/strategic-merge-patch+json'
        }

        def response = doHttpRequest(method,
                clusterEndPoint,
                resourceUri,
                ['Authorization' : accessToken, 'Content-Type': contentType],
                /*failOnErrorCode*/ true,
                resourceDetails)

        def str = response.data ? (new JsonBuilder(response.data)).toPrettyString(): response.data
        logger INFO, "API response: $str"

        response
    }

    def convertVolumes(data){
        def jsonData = parseJsonToList(data)
        def result = []
        for (item in jsonData){
            def name = formatName(item.name)
            if(item.hostPath){
                result << [name: name, hostPath: [path : item.hostPath, type: "Directory"]]
            } else {
                result << [name: name, emptyDir: {}]
            }
        }
        return (new JsonBuilder(result))

    }

    boolean isCanaryDeployment(def args) {
        toBoolean(getServiceParameter(args, 'canaryDeployment'))
    }

    String getDeploymentStrategy(args) {
        getServiceParameter(args, 'deploymentStrategy')
    }

    def validateUniquePorts(def args) {

        def uniquePortNames = []
        for (container in args.container) {
            for (port in container.port) {
                if (!uniquePortNames.contains(port.portName)) {
                    uniquePortNames << port.portName
                } else {
                    handleError("Duplicate port name ${port.portName} found in ${container.containerName} container definition.")
                }
            }
        }
    }

    String buildDeploymentPayload(def args, def existingDeployment, def imagePullSecretsList){

        if (!args.defaultCapacity) {
            args.defaultCapacity = 1
        }

        def deploymentStrategy = getDeploymentStrategy(args)
        println new JsonBuilder(args).toPrettyString()

        def json = new JsonBuilder()
        //Get the message calculation out of the way
        def replicaCount
        def maxSurgeValue
        def maxUnavailableValue
        boolean isCanary = isCanaryDeployment(args)

        if (isCanary) {
            replicaCount = getServiceParameter(args, 'numberOfCanaryReplicas', 1).toInteger()
            maxSurgeValue = 1
            maxUnavailableValue = 1
        } else {
            if (deploymentStrategy && deploymentStrategy == 'rollingDeployment') {
                def minAvailabilityCount = getServiceParameter(args, 'minAvailabilityCount')
                def minAvailabilityPercentage = getServiceParameter(args, 'minAvailabilityPercentage')
                def maxRunningCount = getServiceParameter(args, 'maxRunningCount')
                def maxRunningPercentage = getServiceParameter(args, 'maxRunningPercentage')

                if (!(minAvailabilityPercentage as boolean ^ minAvailabilityCount as boolean)) {
                    throw new PluginException("Either minAvailabilityCount or minAvailabilityPercentage must be set")
                }
                if (!(maxRunningPercentage as boolean ^ maxRunningCount as boolean)) {
                    throw new PluginException("Either maxRunningCount or maxRunningPercentage must be set")
                }

                replicaCount = args.defaultCapacity.toInteger()
                maxSurgeValue = maxRunningCount ? maxRunningCount.toInteger() : "${maxRunningPercentage.toInteger() + 100}%"

                if (minAvailabilityCount) {
                    maxUnavailableValue = args.defaultCapacity.toInteger() - minAvailabilityCount.toInteger()
                }
                else {
                    maxUnavailableValue = "${100 - minAvailabilityPercentage.toInteger()}%"
                }
            }
            else {

                replicaCount = args.defaultCapacity.toInteger()
                maxSurgeValue = args.maxCapacity ? (args.maxCapacity.toInteger() - args.defaultCapacity.toInteger()) : 1
                maxUnavailableValue = args.minCapacity ?
                    (args.defaultCapacity.toInteger() - args.minCapacity.toInteger()) : 1
            }

        }

        def volumeData = convertVolumes(args.volumes)
        def serviceName = getServiceNameToUseForDeployment(args)
        def deploymentName = getDeploymentName(args)
        def selectorLabel = getSelectorLabelForDeployment(args, serviceName, isCanary)

        String apiPath = versionSpecificAPIPath('deployments')
        int deploymentTimeoutInSec = getServiceParameter(args, 'deploymentTimeoutInSec', 120).toInteger()

        def deploymentFlag = isCanary ? 'canary' : 'stable'

        def result = json {
            kind "Deployment"
            apiVersion apiPath
            metadata {
                name deploymentName
            }
            spec {
                replicas replicaCount
                progressDeadlineSeconds deploymentTimeoutInSec
                strategy {
                    rollingUpdate {
                        maxUnavailable maxUnavailableValue
                        maxSurge maxSurgeValue
                    }
                }
                selector {
                    matchLabels {
                        "ec-svc" selectorLabel
                        "ec-track" deploymentFlag
                    }
                }
                template {
                    metadata {
                        name deploymentName
                        labels {
                            "ec-svc" selectorLabel
                            "ec-track" deploymentFlag
                        }
                    }
                    spec{
                        containers(args.container.collect { svcContainer ->
                            def limits = [:]
                            if (svcContainer.memoryLimit) {
                                limits.memory = "${svcContainer.memoryLimit}M"
                            }
                            if (svcContainer.cpuLimit) {
                                Integer cpu = convertCpuToMilliCpu(svcContainer.cpuLimit.toFloat())
                                limits.cpu = "${cpu}m"
                            }

                            def requests = [:]
                            if (svcContainer.memorySize) {
                                requests.memory = "${svcContainer.memorySize}M"
                            }
                            if (svcContainer.cpuCount) {
                                Integer cpu = convertCpuToMilliCpu(svcContainer.cpuCount.toFloat())
                                requests.cpu = "${cpu}m"
                            }

                            def containerResources = [:]
                            if (limits) {
                                containerResources.limits = limits
                            }
                            if (requests) {
                                containerResources.requests = requests
                            }

                            def livenessProbe = [:]
                            def readinessProbe = [:]

                            // Only HTTP based Liveness probe is supported
                            if(getServiceParameter(svcContainer, 'livenessHttpProbePath') && getServiceParameter(svcContainer, 'livenessHttpProbePort')){
                                def httpHeader = [name:"", value: ""]
                                livenessProbe = [httpGet:[path:"", port:"", httpHeaders:[httpHeader]]]
                                livenessProbe.httpGet.path = getServiceParameter(svcContainer, 'livenessHttpProbePath')
                                livenessProbe.httpGet.port = (getServiceParameter(svcContainer, 'livenessHttpProbePort')).toInteger()

                                httpHeader.name = getServiceParameter(svcContainer, 'livenessHttpProbeHttpHeaderName')
                                httpHeader.value = getServiceParameter(svcContainer, 'livenessHttpProbeHttpHeaderValue')

                                def livenessInitialDelay = getServiceParameter(svcContainer, 'livenessInitialDelay')
                                livenessProbe.initialDelaySeconds = livenessInitialDelay.toInteger()
                                def livenessPeriod = getServiceParameter(svcContainer, 'livenessPeriod')
                                livenessProbe.periodSeconds = livenessPeriod.toInteger()
                            } else {
                                livenessProbe = null
                            }

                            def readinessCommand = getServiceParameter(svcContainer, 'readinessCommand')
                            if(readinessCommand){
                                readinessProbe = [exec: [command:[:]]]
                                readinessProbe.exec.command = ["${readinessCommand}"]

                                def readinessInitialDelay = getServiceParameter(svcContainer, 'readinessInitialDelay')
                                readinessProbe.initialDelaySeconds = readinessInitialDelay.toInteger()
                                def readinessPeriod = getServiceParameter(svcContainer, 'readinessPeriod')
                                readinessProbe.periodSeconds = readinessPeriod.toInteger()
                            } else {
                                readinessProbe = null
                            }


                            [
                                    name: formatName(svcContainer.containerName),
                                    image: "${svcContainer.imageName}:${svcContainer.imageVersion?:'latest'}",
                                    command: svcContainer.entryPoint?.split(','),
                                    args: svcContainer.command?.split(','),
                                    livenessProbe: livenessProbe,
                                    readinessProbe: readinessProbe,
                                    ports: svcContainer.port?.collect { port ->
                                        [
                                                name: formatName(port.portName),
                                                containerPort: port.containerPort.toInteger(),
                                                protocol: "TCP"
                                        ]
                                    },
                                    volumeMounts: (parseJsonToList(svcContainer.volumeMounts)).collect { mount ->
                                                        [
                                                            name: formatName(mount.name),
                                                            mountPath: mount.mountPath
                                                        ]

                                        },
                                    env: svcContainer.environmentVariable?.collect { envVar ->
                                        [
                                                name: envVar.environmentVariableName,
                                                value: envVar.value
                                        ]
                                    },
                                    resources: containerResources
                            ]
                        })
                        imagePullSecrets( imagePullSecretsList?.collect { pullSecret ->
                            [name: pullSecret]
                        })
                        volumes(volumeData.content)
                    }
                }

            }
        }

        def payload = existingDeployment
        if (payload) {
            payload = mergeObjs(payload, result)
        } else {
            payload = result
        }
        return ((new JsonBuilder(payload)).toPrettyString())
    }

    def addServiceParameters(def json, Map args) {

        def value = getServiceParameter(args, 'serviceType', 'LoadBalancer')
        json.type value

        if (value == 'LoadBalancer') {

            value = getServiceParameter(args, 'loadBalancerIP')
            if (value != null) {
                json.loadBalancerIP value
            }
            value = getServiceParameterArray(args, 'loadBalancerSourceRanges')
            if (value != null) {
                json.loadBalancerSourceRanges value
            }
        }

        value = getServiceParameter(args, 'sessionAffinity', 'None')
        if (value != null) {
            json.sessionAffinity value
        }

    }

    def getServiceParameter(Map args, String parameterName, def defaultValue = null) {
        def result = args.parameterDetail?.find {
            it.parameterName == parameterName
        }?.parameterValue

        // expand any property references in the service parameter if required
        if (result && result.toString().contains('$[')) {
            EFClient efClient = new EFClient()
            result = efClient.expandString(result.toString())
        }

        // not using groovy truthiness so that we can account for 0
        return result != null && result.toString().trim() != '' ? result.toString().trim() : defaultValue
    }

    def getServiceParameterArray(Map args, String parameterName, String defaultValue = null) {
        def value = getServiceParameter(args, parameterName, defaultValue)
        value?.toString()?.tokenize(',')
    }


    String buildServicePayload(Map args, def deployedService){

        def serviceName = getServiceNameToUseForDeployment(args)
        def canary = isCanaryDeployment(args)
        def selectorLabel = getSelectorLabelForDeployment(args, serviceName, canary)
        def json = new JsonBuilder()
        def portMapping = []

        if (canary) {
            if (!deployedService) {
                handleError("Canary deployments can only be performed for existing services. Service '$serviceName' not found in the cluster.")
            } else {
                logger INFO, "Performing canary deployment, hence skipping service creation/update."
            }
        }

        portMapping = args.port.collect { svcPort ->
                    [
                            port: svcPort.listenerPort.toInteger(),
                            //name is required for Kubernetes if more than one port is specified so auto-assign
                            name: formatName(svcPort.portName),
                            targetPort: svcPort.subport?:svcPort.listenerPort.toInteger(),
                            // default to TCP which is the default protocol if not set
                            //protocol: svcPort.protocol?: "TCP"
                            protocol: "TCP"
                    ]
                }

        if (portMapping.size() == 0) {
            logger WARNING, "The service '$serviceName' is being created as a 'headless' service since there are no ports defined for the service."
        }

        def serviceId = args.serviceId

        def result = json {
            kind "Service"
            apiVersion "v1"

            metadata {
                name serviceName
                labels {
                    "ec-svc-id" serviceId
                }
            }
            //Kubernetes plugin injects this service selector
            //to link the service to the pod that this
            //Deploy service encapsulates.
            spec {

                selector {
                    "ec-svc" selectorLabel
                }

                if (portMapping.size()==0){

                    clusterIP("None")
                } else {
                    // Add LoadBalanceIP and other override
                    // parameters only if portMappings are defined
                    this.addServiceParameters(delegate, args)
                    ports(portMapping)
                }
            }
        }

        def payload = deployedService
        if (payload) {
            payload = mergeObjs(payload, result)
        } else {
            payload = result
        }

        return (new JsonBuilder(payload)).toPrettyString()
    }

    def convertCpuToMilliCpu(float cpu) {
        return cpu * 1000 as int
    }

    Object doHttpHead(String requestUrl, String requestUri, String accessToken, boolean failOnErrorCode = true, Map queryArgs){
        doHttpRequest(HEAD,
                      requestUrl,
                      requestUri,
                      ['Authorization' : accessToken],
                      failOnErrorCode,
                      null,
                      queryArgs)
    }

    Object doHttpGet(String requestUrl, String requestUri, String accessToken, boolean failOnErrorCode = true, requestBody = null, query = null) {

        doHttpRequest(GET,
                requestUrl,
                requestUri,
                ['Authorization' : accessToken],
                failOnErrorCode, requestBody, query)
    }

    Object doHttpGet(String requestUrl, String requestUri, String accessToken, boolean failOnErrorCode = true, Map queryArgs) {

        doHttpRequest(GET,
                requestUrl,
                requestUri,
                ['Authorization' : accessToken, 'Content-Type': 'application/json'],
                failOnErrorCode,
                null,
                queryArgs)
    }

    Object doHttpPost(String requestUrl, String requestUri, String accessToken, String requestBody, boolean failOnErrorCode = true) {

        doHttpRequest(POST,
                requestUrl,
                requestUri,
                ['Authorization' : accessToken],
                failOnErrorCode,
                requestBody)
    }

    Object doHttpPut(String requestUrl, String requestUri, String accessToken, String requestBody, boolean failOnErrorCode = true) {

        doHttpRequest(PUT,
                requestUrl,
                requestUri,
                ['Authorization' : accessToken],
                failOnErrorCode,
                requestBody)
    }

    Object doHttpPut(String requestUrl, String requestUri, String accessToken, Object requestBody, boolean failOnErrorCode = true, Map queryArgs) {
        doHttpRequest(PUT,
                      requestUrl,
                      requestUri,
                      ['Authorization' : accessToken],
                      failOnErrorCode,
                      requestBody,
                      queryArgs)
    }

    Object doHttpDelete(String requestUrl, String requestUri, String accessToken, boolean failOnErrorCode = true) {

        doHttpRequest(DELETE,
                requestUrl,
                requestUri,
                ['Authorization' : accessToken],
                failOnErrorCode)
    }

    boolean isVersionGreaterThan17() {
        try {
            float version = Float.parseFloat(this.kubernetesVersion)
            version >= 1.8
        } catch (NumberFormatException ex) {
            logger WARNING, "Invalid Kubernetes version '$kubernetesVersion'"
            true
        }
    }

    boolean isVersionGreaterThan15() {
        try {
            float version = Float.parseFloat(this.kubernetesVersion)
            version >= 1.6
        } catch (NumberFormatException ex) {
            logger WARNING, "Invalid Kubernetes version '$kubernetesVersion'"
            // default to considering this > 1.5 version
            true
        }
    }

    def setVersion(def pluginConfig) {
        // read the version from the plugin config
        // if it is defined
        if (pluginConfig.kubernetesVersion) {
            //validate that the version is numeric
            try {
                def versionStr = pluginConfig.kubernetesVersion.toString()
                Float.parseFloat(versionStr)
                this.kubernetesVersion = versionStr
            } catch (NumberFormatException ex) {
                logger WARNING, "Invalid Kubernetes version specified: '$versionStr', " +
                        "defaulting to version '$kubernetesVersion'"
            }
        }
        logger INFO, "Using Kubernetes API version '$kubernetesVersion' based on plugin configuration\n"
    }

    String versionSpecificAPIPath(String resource) {
        switch (resource) {
            case 'deployments':
                return isVersionGreaterThan15() ? ( isVersionGreaterThan17() ? 'apps/v1beta2' : 'apps/v1beta1'): 'extensions/v1beta1'
            default:
                handleError("Unsupported resource '$resource' for determining version specific API path")
        }
    }

    String getSelectorLabelForDeployment(def serviceDetails, String serviceName, boolean isCanary) {
        if (isCanary) {
            serviceName
        } else {
            getDeploymentName(serviceDetails)
        }
    }

    String getServiceNameToUseForDeployment (def serviceDetails) {
        makeNameDNS1035Compliant(getServiceParameter(serviceDetails, "serviceNameOverride", serviceDetails.serviceName))
    }

    String getDeploymentName(def serviceDetails) {
        if (isCanaryDeployment(serviceDetails)) {
            constructCanaryDeploymentName(serviceDetails)
        } else {
            def serviceName = getServiceNameToUseForDeployment(serviceDetails)
            makeNameDNS1035Compliant(getServiceParameter(serviceDetails, "deploymentNameOverride", serviceName))
        }
    }

    String constructCanaryDeploymentName(def serviceDetails) {
        String name = getServiceNameToUseForDeployment (serviceDetails)
        "${name}-canary"
    }

    String makeNameDNS1035Compliant(String name){
        return formatName(name).replaceAll('\\.', '-')
    }
}



public class OpenShiftClient extends KubernetesClient {

    def createOrUpdatePlatformSpecificResources(String clusterEndpoint, String namespace, def serviceDetails, String accessToken) {
        if (OFFLINE) return null
        createOrUpdateRoute(clusterEndpoint, namespace, serviceDetails, accessToken)
    }

    def getRoutes(String clusterEndpoint, String namespace, String accessToken) {
        println clusterEndpoint
        def response = doHttpGet(clusterEndpoint, "/oapi/v1/namespaces/${namespace}/routes", accessToken, true)
        logger DEBUG, "Routes: ${response}"
        return response?.data?.items
    }

    def createOrUpdateRoute(String clusterEndpoint, String namespace, def serviceDetails, String accessToken) {
        String routeName = getServiceParameter(serviceDetails, 'routeName')

        if (!routeName) {
            //bail out - not creating route if weren't asked to
            return null
        }

        def response = doHttpGet(clusterEndpoint,
                "/oapi/v1/namespaces/${namespace}/routes/${routeName}",
                accessToken, /*failOnErrorCode*/ false)
        if (response.status == 200){
            logger INFO, "Route $routeName found in $namespace, updating route ..."
            createOrUpdateRoute(/*existingRoute*/ response.data, routeName, clusterEndpoint, namespace, serviceDetails, accessToken)
        } else if (response.status == 404){
            logger INFO, "Route $routeName does not exist in $namespace, creating route ..."
            createOrUpdateRoute(/*existingRoute*/ null, routeName, clusterEndpoint, namespace, serviceDetails, accessToken)
        } else {
            handleError("Route check failed. ${response.statusLine}")
        }
    }


    def createOrUpdateRoute(def existingRoute, String routeName, String clusterEndpoint, String namespace, def serviceDetails, String accessToken) {
        String routeHostname = getServiceParameter(serviceDetails, 'routeHostname')
        String routePath = getServiceParameter(serviceDetails, 'routePath', '/')
        String routeTargetPort = getServiceParameter(serviceDetails, 'routeTargetPort')

        def payload = buildRoutePayload(routeName, routeHostname, routePath, routeTargetPort, serviceDetails, existingRoute)

        def createRoute = existingRoute == null
        doHttpRequest(createRoute ? POST : PUT,
                clusterEndpoint,
                createRoute?
                        "/oapi/v1/namespaces/${namespace}/routes" :
                        "/oapi/v1/namespaces/${namespace}/routes/${routeName}",
                ['Authorization' : accessToken],
                /*failOnErrorCode*/ true,
                payload)
    }

    String buildRoutePayload(String routeName, String routeHostname, String routePath, String routeTargetPort, def serviceDetails, def existingRoute) {
        def serviceName = getServiceNameToUseForDeployment(serviceDetails)
        def json = new JsonBuilder()
        def result = json{
            kind "Route"
            apiVersion "v1"
            metadata {
                name routeName
            }
            spec {
                if (routeHostname) {
                    host routeHostname
                }
                path routePath
                to {
                    kind "Service"
                    name serviceName
                }
                if (routeTargetPort) {
                    port {
                        targetPort routeTargetPort
                    }
                }
            }
        }
        // build the final payload by merging with the existing
        // route definition
        def payload = existingRoute
        if (payload) {
            payload = mergeObjs(payload, result)
        } else {
            payload = result
        }

        return (new JsonBuilder(payload)).toPrettyString()
    }

    def undeployService(
            EFClient efClient,
            String accessToken,
            String clusterEndpoint,
            String namespace,
            String serviceName,
            String serviceProjectName,
            String applicationName,
            String applicationRevisionId,
            String clusterName,
            String envProjectName,
            String environmentName,
            String serviceEntityRevisionId = null) {

        super.undeployService(
                efClient,
                accessToken,
                clusterEndpoint,
                namespace,
                serviceName,
                serviceProjectName,
                applicationName,
                applicationRevisionId,
                clusterName,
                envProjectName,
                environmentName,
                serviceEntityRevisionId)

        def serviceDetails = efClient.getServiceDeploymentDetails(
                serviceName,
                serviceProjectName,
                applicationName,
                applicationRevisionId,
                clusterName,
                envProjectName,
                environmentName,
                serviceEntityRevisionId)

        removeRoute(clusterEndpoint, namespace, serviceDetails, accessToken)
    }

    def removeRoute(String clusterEndpoint, String namespace, def serviceDetails, String accessToken) {

        String routeName = getServiceParameter(serviceDetails, 'routeName')
        if (!routeName) {
            //bail out - nothing to do if the route is not specified
            return null
        }

        def response = doHttpGet(clusterEndpoint,
                "/oapi/v1/namespaces/${namespace}/routes/${routeName}",
                accessToken, /*failOnErrorCode*/ false)

        if (response.status == 200){
            logger DEBUG, "Route $routeName found in $namespace"

            def existingRoute = response.data
            def serviceName = getServiceNameToUseForDeployment(serviceDetails)
            if (existingRoute?.spec?.to?.kind == 'Service' && existingRoute?.spec?.to?.name == serviceName) {
                logger DEBUG, "Deleting route $routeName in $namespace"

                doHttpRequest(DELETE,
                        clusterEndpoint,
                        "/oapi/v1/namespaces/${namespace}/routes/${routeName}",
                        ['Authorization' : accessToken],
                        /*failOnErrorCode*/ true)
            }

        } else if (response.status == 404){
            logger INFO, "Route $routeName does not exist in $namespace, no route to remove"
        } else {
            handleError("Route check failed. ${response.statusLine}")
        }
    }

    def convertVolumes(data){
        def jsonData = parseJsonToList(data)
        def result = []
        for (item in jsonData){
            def name = formatName(item.name)
            if(item.hostPath){
                result << [name: name, hostPath: [path : item.hostPath]]
            } else {
                result << [name: name, emptyDir: {}]
            }
        }
        return (new JsonBuilder(result))
    }


    def getDeploymentConfigs(String clusterEndPoint, String namespace, String accessToken, parameters = [:]) {
        def path = "/oapi/v1/namespaces/${namespace}/deploymentconfigs"
        def response = doHttpGet(clusterEndPoint,
            path,
            accessToken, /*failOnErrorCode*/ false, null)
        def tempDeployments = []
        response?.data?.items?.each{ deployment ->
            def fit = false
            deployment?.spec?.selector.each{ k, v ->
                parameters.labelSelector.split(',').each{ selector ->
                    if ((k + '=' + v) == selector){
                        fit = true
                    }
                }
            }
            if (fit){
                tempDeployments.push(deployment)
            }
        }

        response.data.items = tempDeployments
        def str = response.data ? (new JsonBuilder(response.data)).toPrettyString(): response.data
        logger DEBUG, "Deployments found: $str"

        response.status == 200 ? response.data : null
    }



}

 
@Grab('org.yaml:snakeyaml:1.19')
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import org.yaml.snakeyaml.Yaml

public class ImportFromTemplate extends EFClient {
    static final String CREATED_DESCRIPTION = "Created by ImportFromTemplate"
    //static final String DELIMITER = "---"
    static def REPORT_TEMPLATE = '''
    <!DOCTYPE HTML>
<html lang="en">
  <head>
  <title>Report for unsupported fields</title>
    <style>
      body, html {
        padding: 0;
        margin: 0;
      }

      body {
        font-family: "Helvetica Neue", Helvetica, Arial, sans-serif;
        font-size: 14px;
        line-height: 1.5;
        color: #333;
      }

      .wrap {
        margin: 0 auto;
        width: 96%;
        min-width: 980px;
        padding: 0 10px;
      }

      .header {
        padding: 10px 0 10px 0;
        background-color: #333;
        margin-bottom: 1em;
        min-width: 1000px;
      }

      h1,
      .h1,
      h2,
      .h2,
      h3,
      .h3 {
        margin: 0.67em 0;
        font-weight: 700;
        line-height: 1.3;
      }

      h1,
      .h1 {
        font-size: 28px;
      }

      h2,
      .h2 {
        font-size: 24px;
      }

      h3,
      .h3 {
        font-size: 20px;
      }

      img {
        display: inline-block;
        vertical-align: middle;
      }

      .list {
        margin: 1em 0;
        padding-left: 0;
        list-style-type: none;
      }

      .list li {
        margin-bottom: 10px;
      }

      table {
        width: 100%;
        max-width: 100%;
        border-collapse: collapse;
        border: 1px solid #d0d0d0;
      }

      .table-responsive {
        margin: 1em 0;
        min-height: .01%;
        overflow-x: auto;
      }

      th.text-left {
        text-align: left;
      }

      .text-center {
        text-align: center;
      }

      td.text-left {
        text-align: left;
      }

      th,
      td {
        padding: 5px 10px;
        border: 1px solid #d0d0d0;
      }

      th {
        background-color: #f0f0f0;
      }

    </style>
  </head>
  <body>

    <div class="header">
      <h1 class="wrap"><img src="data:image/svg+xml;base64,PD94bWwgdmVyc2lvbj0iMS4wIiBlbmNvZGluZz0iVVRGLTgiIHN0YW5kYWxvbmU9Im5vIj8+PCFET0NUWVBFIHN2ZyBQVUJMSUMgIi0vL1czQy8vRFREIFNWRyAxLjEvL0VOIiAiaHR0cDovL3d3dy53My5vcmcvR3JhcGhpY3MvU1ZHLzEuMS9EVEQvc3ZnMTEuZHRkIj48c3ZnICAgeG1sbnM6c3ZnPSJodHRwOi8vd3d3LnczLm9yZy8yMDAwL3N2ZyIgICB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciICAgdmVyc2lvbj0iMS4xIiAgIHdpZHRoPSI3MTkiICAgaGVpZ2h0PSI3NjgiICAgdmlld0JveD0iLTEuNzMzODY3MSAtMS43MzM4NjcxIDYxLjI2MzMwNDIgNjUuNDA4MjI5MiIgICBpZD0ic3ZnNDU0NTMiPiAgPGRlZnMgICAgIGlkPSJkZWZzNDU0NTUiIC8+ICA8cGF0aCAgICAgZD0ibSA1NC4yMjc4MiwxMS45ODY2MTUgYyAtMC41NzI1LC0xLjE4MjUgLTEuMjM1LC0yLjMyMzc1MDQgLTIuMDAzNzUsLTMuNDAwMDAwNCBsIC04LjIxMjUsMi45ODg3NTA0IGMgMC45NTUsMC45Nzc1IDEuNzU3NSwyLjA3NjI1IDIuNDEzNzUsMy4yNTEyNSBsIDcuODAyNSwtMi44NCB6IG0gLTM2LjMwMjg3LDkuMDgzMzggLTguMjE1LDIuOTg4NzUgYyAwLjEwNSwxLjMxNzUgMC4zMzI1LDIuNjE4NzUgMC42NTEyNSwzLjg5Mzc1IGwgNy44MDM3NSwtMi44NDEyNSBjIC0wLjI1Mzc1LC0xLjMyIC0wLjM0Mzc1LC0yLjY4IC0wLjI0LC00LjA0MTI1IiAgICAgaWQ9InBhdGg0NDU2MCIgICAgIHN0eWxlPSJmaWxsOiNjMjIxMzM7ZmlsbC1vcGFjaXR5OjE7ZmlsbC1ydWxlOm5vbnplcm87c3Ryb2tlOm5vbmUiIC8+ICA8cGF0aCAgICAgZD0ibSAzNi4xNTYyLDkuNjYwMTE0NiBjIDEuNzA4NzUsMC43OTc1MDA0IDMuMTg4NzUsMS44ODUwMDA0IDQuNDM3NSwzLjE2MDAwMDQgbCA4LjIxMjUsLTIuOTg4NzUwNCBjIC0yLjI3NSwtMy4xOTI1IC01LjM3Mzc1LC01Ljg2IC05LjE3LC03LjYzMTI1IC0xMS43NDEyNSwtNS40NzUgLTI1Ljc0ODc1LC0wLjM3NzUgLTMxLjIyMjUsMTEuMzYyNTAwNCAtMS43NzI1LDMuNzk4NzUgLTIuNDMxMjUsNy44MzM3NSAtMi4xMjEyNSwxMS43NDEyNSBsIDguMjEzNzUsLTIuOTg4NzUgYyAwLjEzNjI1LC0xLjc4IDAuNTcsLTMuNTYzNzUgMS4zNjYyNSwtNS4yNzM3NSBDIDE5LjQyOTk1LDkuNDEzODY0NiAyOC41Mjg3LDYuMTAzODY0NiAzNi4xNTYyLDkuNjYwMTE0NiIgICAgIGlkPSJwYXRoNDQ1NjQiICAgICBzdHlsZT0iZmlsbDojZGIyMTJlO2ZpbGwtb3BhY2l0eToxO2ZpbGwtcnVsZTpub256ZXJvO3N0cm9rZTpub25lIiAvPiAgPHBhdGggICAgIGQ9Im0gNDQuOTE1ODIsMjQuNjY4MjQ1IGMgLTAuMTMxMjUsMS43Nzg3NSAtMC41OCwzLjU2MjUgLTEuMzc4NzUsNS4yNzM3NSAtMy41NTYyNSw3LjYyODc1IC0xMi42NTYyNSwxMC45Mzg3NSAtMjAuMjgyNSw3LjM4MjUgLTEuNzExMjUsLTAuNzk4NzUgLTMuMjAyNSwtMS44Nzc1IC00LjQ0NjI1LC0zLjE1NSBsIC04LjE5NjI1LDIuOTgyNSBjIDIuMjcsMy4xOTI1IDUuMzY1LDUuODYxMjUgOS4xNjM3NSw3LjYzMzc1IDExLjc0MTI1LDUuNDczNzUgMjUuNzQ2MjUsMC4zNzYyNSAzMS4yMjEyNSwtMTEuMzY1IDEuNzczNzUsLTMuNzk2MjUgMi40Mjc1LC03LjgzMTI1IDIuMTE1LC0xMS43MzUgbCAtOC4xOTYyNSwyLjk4MjUgeiIgICAgIGlkPSJwYXRoNDQ1NzIiICAgICBzdHlsZT0iZmlsbDojZGIyMTJlO2ZpbGwtb3BhY2l0eToxO2ZpbGwtcnVsZTpub256ZXJvO3N0cm9rZTpub25lIiAvPiAgPHBhdGggICAgIGQ9Im0gNDYuOTM1NDUsMTQuNjQxMTE1IC03LjgwMzc1LDIuODQgYyAxLjQ1LDIuNTk3NSAyLjEzNSw1LjU4NzUgMS45MSw4LjU5NSBsIDguMTk2MjUsLTIuOTgxMjUgYyAtMC4yMzUsLTIuOTQxMjUgLTEuMDE4NzUsLTUuODEyNSAtMi4zMDI1LC04LjQ1Mzc1IG0gLTM2LjA2Mjc1LDEzLjEyNCAtNy44MDM3NSwyLjg0MjUgYyAwLjcxNjI1LDIuODQ1IDEuOTYsNS41NDg3NSAzLjY3LDcuOTU1IGwgOC4xOTUsLTIuOTgzNzUgYyAtMi4xMDM3NSwtMi4xNiAtMy41MDI1LC00Ljg5Mzc1IC00LjA2MTI1LC03LjgxMzc1IiAgICAgaWQ9InBhdGg0NDU3NiIgICAgIHN0eWxlPSJmaWxsOiNlYjIxMjY7ZmlsbC1vcGFjaXR5OjE7ZmlsbC1ydWxlOm5vbnplcm87c3Ryb2tlOm5vbmUiIC8+ICA8cGF0aCAgICAgZD0ibSA1My4wMzgzMiw5LjgyMjk5NDYgYyAtMC4yNTg3NSwtMC40MiAtMC41Mjc1LC0wLjgzMzc1IC0wLjgxMzc1LC0xLjIzNjI1IGwgLTguMjEyNSwyLjk4ODc1MDQgYyAwLjM2MTI1LDAuMzcgMC42OTM3NSwwLjc2MjUgMS4wMTEyNSwxLjE2NSBsIDguMDE1LC0yLjkxNzUwMDQgeiBNIDE3Ljg5MzU3LDIyLjcxOTM2NSBjIC0wLjAyLC0wLjU0NzUgLTAuMDExMywtMS4wOTc1IDAuMDMxMiwtMS42NDg3NSBsIC04LjIxNSwyLjk4ODc1IGMgMC4wNDI1LDAuNTI2MjUgMC4xMDg3NSwxLjA0ODc1IDAuMTg3NSwxLjU3IGwgNy45OTYyNSwtMi45MSB6IiAgICAgaWQ9InBhdGg0NDU4NCIgICAgIHN0eWxlPSJmaWxsOiNhZDIxM2I7ZmlsbC1vcGFjaXR5OjE7ZmlsbC1ydWxlOm5vbnplcm87c3Ryb2tlOm5vbmUiIC8+ICA8cGF0aCAgICAgZD0ibSA1My4xMTE1NywyMS42ODU2MTUgLTguMTk2MjUsMi45ODI1IGMgLTAuMDg2MywxLjE4IC0wLjMxNjI1LDIuMzYyNSAtMC42OTI1LDMuNTI1IGwgOC45MjEyNSwtMy4yNTI1IGMgMC4wNjM4LC0xLjA5IDAuMDUzNywtMi4xNzc1IC0wLjAzMjUsLTMuMjU1IG0gLTQyLjQ5ODc1LDE1LjQ2NzUgYyAwLjYzMTI1LDAuODg4NzUgMS4zMywxLjczNjI1IDIuMDg4NzUsMi41MzYyNSBsIDguOTIyNSwtMy4yNTM3NSBjIC0xLjA0MjUsLTAuNjUyNSAtMS45ODUsLTEuNDEzNzUgLTIuODE2MjUsLTIuMjY2MjUgbCAtOC4xOTUsMi45ODM3NSB6IiAgICAgaWQ9InBhdGg0NDU4OCIgICAgIHN0eWxlPSJmaWxsOiNiYTIxMzM7ZmlsbC1vcGFjaXR5OjE7ZmlsbC1ydWxlOm5vbnplcm87c3Ryb2tlOm5vbmUiIC8+ICA8cGF0aCAgICAgZD0ibSA1Mi42ODkzMiw1NC41MzIxMTUgMCwwLjc0IDIuMTQ2MjUsMCAwLDYuNTUzNzUgMC44MTI1LDAgMCwtNi41NTM3NSAyLjE0NzUsMCAwLC0wLjc0IC01LjEwNjI1LDAgeiBtIC00LjkyODUsMC43MzkzOCAwLDIuNDE3NSAyLDAgMCwwLjc0IC0yLDAgMCwzLjM5NjI1IC0wLjgxMjUsMCAwLC03LjI5MjUgNC4yODI1LDAgMCwwLjczODc1IC0zLjQ3LDAgeiBtIC0zLjcxNzc1LC0wLjczODUgMC44MTI1LDAgMCw3LjI5Mzc1IC0wLjgxMjUsMCAwLC03LjI5Mzc1IHogbSAtMi45MDQyNSw3LjI5Mjg3IDAsLTMuNDM4NzUgLTMuNjI2MjUsMCAwLDMuNDM4NzUgLTAuODEyNSwwIDAsLTcuMjkzNzUgMC44MTI1LDAgMCwzLjExNjI1IDMuNjI2MjUsMCAwLC0zLjExNjI1IDAuODEyNSwwIDAsNy4yOTM3NSAtMC44MTI1LDAgeiBtIC04LjY2Njc1LDAuMTE0NjMgYyAtMC45OSwwIC0xLjg3NjI1LC0wLjQyNzUgLTIuNDQ4NzUsLTEuMDEgbCAwLjU0MjUsLTAuNjA1IGMgMC41NTEyNSwwLjUzMTI1IDEuMTg3NSwwLjg3NjI1IDEuOTM3NSwwLjg3NjI1IDAuOTY4NzUsMCAxLjU3Mzc1LC0wLjQ4IDEuNTczNzUsLTEuMjUxMjUgMCwtMC42Nzc1IC0wLjQwNjI1LC0xLjA2MjUgLTEuNzQsLTEuNTQyNSAtMS41NzM3NSwtMC41NjI1IC0yLjEwNSwtMS4wNzI1IC0yLjEwNSwtMi4xMjUgMCwtMS4xNjc1IDAuOTE2MjUsLTEuODY2MjUgMi4yODEyNSwtMS44NjYyNSAwLjk4LDAgMS42MDUsMC4yOTI1IDIuMjIsMC43ODI1IGwgLTAuNTIxMjUsMC42MzUgYyAtMC41MzEyNSwtMC40Mzc1IC0xLjAyMTI1LC0wLjY3NzUgLTEuNzUsLTAuNjc3NSAtMS4wMDEyNSwwIC0xLjQxNzUsMC41IC0xLjQxNzUsMS4wNzM3NSAwLDAuNjA1IDAuMjcxMjUsMC45NDc1IDEuNzMsMS40NyAxLjYxNSwwLjU4MjUgMi4xMTUsMS4xMjUgMi4xMTUsMi4yMDg3NSAwLDEuMTQ2MjUgLTAuODk2MjUsMi4wMzEyNSAtMi40MTc1LDIuMDMxMjUgbSAtNS43NDE1LC0wLjExNDYzIC0yLjY3NzUsLTMuOTggYyAtMC4xNzc1LC0wLjI3MTI1IC0wLjQxNzUsLTAuNjM2MjUgLTAuNTExMjUsLTAuODIzNzUgMCwwLjI3MTI1IDAuMDIxMiwxLjE4NzUgMC4wMjEyLDEuNTkzNzUgbCAwLDMuMjEgLTEuNDM4NzUsMCAwLC03LjI5Mzc1IDEuMzk2MjUsMCAyLjU4NSwzLjg1NSBjIDAuMTc3NSwwLjI3MTI1IDAuNDE2MjUsMC42MzYyNSAwLjUxLDAuODIzNzUgMCwtMC4yNzEyNSAtMC4wMiwtMS4xODc1IC0wLjAyLC0xLjU5NSBsIDAsLTMuMDgzNzUgMS40Mzc1LDAgMCw3LjI5Mzc1IC0xLjMwMjUsMCB6IG0gLTExLjUwMTEyLDAgMCwtNy4yOTM3NSA1LjA2Mzc1LDAgMCwxLjQyNzUgLTMuNjA1LDAgMCwxLjI2MTI1IDIuMDk1LDAgMCwxLjQxNjI1IC0yLjA5NSwwIDAsMS43NjEyNSAzLjc2MTI1LDAgMCwxLjQyNzUgLTUuMjIsMCB6IG0gLTQuMTQ0NzUsLTIuNTgzNjIgLTEuNjA1LDAgMCwyLjU4Mzc1IC0xLjQ1ODc1LDAgMCwtNy4yOTM3NSAzLjE4ODc1LDAgYyAxLjM3NSwwIDIuNTExMjUsMC43NjEyNSAyLjUxMTI1LDIuMzEyNSAwLDEuNjg4NzUgLTEuMTI1LDIuMzk3NSAtMi42MzYyNSwyLjM5NzUgbSAwLjA3MzgsLTMuMjkyNSAtMS42Nzg3NSwwIDAsMS44NzUgMS42OTg3NSwwIGMgMC42Nzc1LDAgMS4wNDI1LC0wLjMxMzc1IDEuMDQyNSwtMC45NDg3NSAwLC0wLjYzNSAtMC40MTc1LC0wLjkyNjI1IC0xLjA2MjUsLTAuOTI2MjUgTSAzLjIxLDYxLjk0MDQ5NSBjIC0xLjkwNzUsMCAtMy4yMSwtMS4zOTYyNSAtMy4yMSwtMy43NTEyNSAwLC0yLjM1NSAxLjMyMzc1LC0zLjc3MjUgMy4yMzEyNSwtMy43NzI1IDEuODk2MjUsMCAzLjE5ODc1LDEuMzk3NSAzLjE5ODc1LDMuNzUyNSAwLDIuMzU1IC0xLjMyMzc1LDMuNzcxMjUgLTMuMjIsMy43NzEyNSBtIC0wLjAxLC02LjA3NSBjIC0xLjAyMTI1LDAgLTEuNjk4NzUsMC44MjM3NSAtMS42OTg3NSwyLjMwMzc1IDAsMS40OCAwLjcwODc1LDIuMzIyNSAxLjczLDIuMzIyNSAxLjAyMTI1LDAgMS42OTc1LC0wLjgyMjUgMS42OTc1LC0yLjMwMjUgMCwtMS40OCAtMC43MDc1LC0yLjMyMzc1IC0xLjcyODc1LC0yLjMyMzc1IiAgICAgaWQ9InBhdGg0NDYyMiIgICAgIHN0eWxlPSJmaWxsOiMyNDFmMjE7ZmlsbC1vcGFjaXR5OjE7ZmlsbC1ydWxlOm5vbnplcm87c3Ryb2tlOm5vbmUiIC8+PC9zdmc+" alt="openshift logo" width="50" height="50" />
        <span style="color: #ffffff;">OpenShift</span>
      </h1>
    </div>

    <div class="wrap">


      <div>
        <h1>Unsupported fields</h1>
        <div class="table-responsive">
          <table>
            <thead>
              <tr>
                <th class="text-center" colspan="3">Information</th>
              <tr>
                <th class="text-center">Type</th>
                <th class="text-center">Name</th>
                <th class="text-center">Field</th>
              </tr>
            </thead>
            <tbody>
                ${tableText}
            </tbody>
          </table>
        </div>
      </div>
    </div>
</body>
</html>

    '''
    static def REPORT_URL_PROPERTY = '/myJob/report-urls/'

    static def KIND_ROUTE = 'Route'
    static def KIND_SERVICE = 'Service'

    def ignoreList = []
    def discoveredSummary = [:]
    def parsedConfigList = []

    Yaml parser = new Yaml()

    def resolveTemplateByParameters(template, parametersMap) {
        parametersMap = parametersMap ?: [:]
        def parametersMapCloned = parametersMap.clone();

        // checking whether there are parameters with default values defined in template
        Yaml parser = new Yaml()
        def parsedTemplate = parser.load(template)
        def parametersWithDefaultValuesFromTemplate = [:]
        parsedTemplate.parameters.each { item ->
            if (item.containsKey('value')) {
                parametersWithDefaultValuesFromTemplate.put(item.name, item.value)
            }

        }

        // expand parameters map by default values for not provided parameters
        parametersWithDefaultValuesFromTemplate.each { parameterName, parameterValue ->
            if (!parametersMapCloned.containsKey(parameterName)) {
                logger INFO, "Parameter '$parameterName' is not provided, using its default value from template: '$parameterValue'"
                parametersMapCloned.put(parameterName, parameterValue)
            }
        }

        // resolve the template by provided parameters or default values
        def resolvedTemplate = template
        parametersMapCloned.each { parameterName, parameterValue ->
            resolvedTemplate = resolvedTemplate.replaceAll(/\$\{{1,2}\s*${parameterName}\s*\}{1,2}/, parameterValue)
        }

        return resolvedTemplate
    }

    def importFromTemplate(fileYAML){
        def efServices = []
        def configList = fileYAML

        def parsedConfig = parser.load(configList)

        parsedConfig.objects.each{ obj ->
            parsedConfigList.push(obj)
        }

        def services
        try {
            services = getParsedServices(parsedConfigList)
        }
        catch(Exception e) {
            println "Failed to find any services in the YAML file. Cause: ${e.message}"
            System.exit(-1)
        }
        logger INFO, "Parsed services: ${services}"

        def deployments
        try {
            deployments = getParsedDeployments(parsedConfigList)
        }
        catch(Exception e) {
            println "Failed to find any deployment configurations in the YAML file. Cause: ${e.message}"
            System.exit(-1)
        }

        if (deployments.size() < 1){
            println "Failed to find any deployment configurations in the YAML file."
            System.exit(-1)
        }
        logger INFO, "Parsed deployments: ${deployments}"

        services.each { kubeService ->
            if (!isSystemService(kubeService)) {
                def allQueryDeployments = []
                def i = 0
                kubeService.spec.selector.each{ k, v ->
                    i = i + 1
                    def queryDeployments = getDeploymentsBySelector(deployments, k, v)
                    if (queryDeployments != null) {
                        queryDeployments.eachWithIndex { deployment, index ->
                            if (index == 0) {
                                allQueryDeployments.push(deployment)
                            } else {
                                logger INFO, "More than one deployment (${deployment.metadata.name}) with the matching label was found for service ${kubeService.metadata.name} with selector ( ${k} : ${v} )."
                            }
                        }
                    }
                }
                def dedupedQueryDeployments = []
                allQueryDeployments.each{ deploy ->
                    def uniq = true
                    dedupedQueryDeployments.each { dedupedDeploy ->
                        if (deploy.metadata.name == dedupedDeploy.metadata.name){
                            uniq = false
                        }
                    }
                    if (uniq){
                        dedupedQueryDeployments.push(deploy)
                    }
                }
                dedupedQueryDeployments.eachWithIndex { deploy, indexDeploy ->
                    def efService = buildServiceDefinition(kubeService, deploy)
                    efServices.push(efService)
                }
            }
        }

        if(ignoreList){
            def textTable = buildReportText(ignoreList)
            publishReport(textTable)
        }

        efServices
    }

    def flattenMap(keyPrefix, input, LinkedHashMap flatten ) {
        input.each { k, v ->
            def key = "${keyPrefix}/${k}".trim()
            if (v instanceof LinkedHashMap) {
                flattenMap(key, v, flatten)
            }
            else if (v instanceof ArrayList){
                flatten = flattenArrayList(key, v, flatten)
            }
            else {
                flatten[key] = v
            }
        }

        flatten
    }

    def flattenArrayList(keyPrefix, value, flatten ){
        value.eachWithIndex{ v, i ->
            def key = "${keyPrefix}[${i}]".trim()
            if (v instanceof LinkedHashMap) {
                flattenMap(key, v, flatten)
            }
            else if (v instanceof ArrayList){

                flattenArrayList(key, v, flatten)
            }
            else{
                flatten[key] = v
            }
        }
        flatten
    }

    def getParsedServices(parsedConfigList){
        def services = []
        parsedConfigList.each { config ->
            if (config.kind == "Service"){
                services.push(config)
            }
        }
        services
    }

    def getParsedDeployments(parsedConfigList){
        def deployments = []
        parsedConfigList.each { config ->
            if (config.kind in ['Deployment','DeploymentConfig']){
                deployments.push(config)
            }
        }
        deployments
    }

    def getDeploymentsBySelector(deployments, key, value){
        def queryDeployments = []
        def first = true
        deployments.each { deployment ->
            def removeLabels = []
            deployment.spec?.template?.metadata?.labels.each{ k, v ->
                if ((k == key) && (v == value)){
                    if (first){
                        queryDeployments.push(deployment)
                        removeLabels.push(k)
                        first = false
                    }
                    else{
                        queryDeployments.push(deployment)
                    }
                }
            }
            if (removeLabels){
                removeLabels.each { keyToRemove ->
                    deployment.spec?.template?.metadata?.labels.remove(keyToRemove)
                }
            }
        }
        queryDeployments
    }

    def getExistingApp(applicationName, projectName){
        def applications = getApplications(projectName)
        def existingApplication = applications?.find {
            it.applicationName == applicationName && it.projectName == projectName
        }
        existingApplication
    }

    def saveToEF(services, projectName, envProjectName, envName, clusterName, String applicationName = null) {
        if (applicationName && !getExistingApp(applicationName, projectName)){
            def app = createApplication(projectName, applicationName)
            logger INFO, "Application ${applicationName} has been created"
            createAppDeployProcess(projectName, applicationName)
            // create link for the application
            def applicationId = app.applicationId
            setEFProperty("/myJob/report-urls/Application: $applicationName", "/flow/#applications/$applicationId")
        }

        def efServices = getServices(projectName, applicationName)
        services.each { service ->
            def svc = createOrUpdateService(projectName, envProjectName, envName, clusterName, efServices, service, applicationName)
            // create links for the service if creating top-level services
            if (svc && !applicationName) {
                def serviceId = svc.serviceId
                setEFProperty("/myJob/report-urls/Microservice: ${svc.serviceName}", "/flow/#services/$serviceId")
            }
        }

        def lines = ["Discovered services: ${discoveredSummary.size()}"]
        discoveredSummary.each { serviceName, containers ->
            def containerNames = containers.collect { k -> k.key }
            if (applicationName) {
                lines.add("${applicationName}: ${serviceName}: ${containerNames.join(', ')}")
            } else {
                lines.add("${serviceName}: ${containerNames.join(', ')}")
            }
        }
        updateJobSummary(lines.join("\n"), /*jobStepSummary*/ true)
    }

    def createOrUpdateService(projectName, envProjectName, envName, clusterName, efServices, service, String applicationName = null) {
        def existingService = efServices.find { s ->
            equalNames(s.serviceName, service.service.serviceName)
        }
        def result
        def serviceName

        logger DEBUG, "Service payload:"
        logger DEBUG, new JsonBuilder(service).toPrettyString()

        if (existingService) {
            serviceName = existingService.serviceName
            logger WARNING, "Service ${existingService.serviceName} already exists, skipping"
            return null
            // Future
            // result = updateEFService(existingService, service)
            // logger INFO, "Service ${existingService.serviceName} has been updated"
        }
        else {
            serviceName = service.service.serviceName
            result = createEFService(projectName, service, applicationName)?.service
            logger INFO, "Service ${serviceName} has been created"
            discoveredSummary[serviceName] = [:]
        }
        assert serviceName

        // Containers
        service.secrets?.each { cred ->
            def credName = getCredName(cred)
            createCredential(projectName, credName, cred.userName, cred.password)
            logger INFO, "Credential $credName has been created"
        }

        service.containers.each { container ->
            service.secrets?.each { secret ->
                if (secret.repoUrl =~ /${container.container.registryUri}/) {
                    container.container.credentialName = getCredName(secret)
                }
            }
            createOrUpdateContainer(projectName, serviceName, container, applicationName)
            mapContainerPorts(projectName, serviceName, container, service, applicationName)
        }
        if (service.serviceMapping && envProjectName && envName && clusterName) {
            createOrUpdateMapping(projectName, envProjectName, envName, clusterName, serviceName, service, applicationName)
        }

        if (applicationName) {
            createAppDeployProcessStep(projectName, applicationName, serviceName)
        }
        result
    }

    def createAppDeployProcess(projectName, applicationName) {
        def processName = 'Deploy'
        createAppProcess(projectName, applicationName, [processName: processName, processType: 'DEPLOY'])
        logger INFO, "Process ${processName} has been created for applicationName: '${applicationName}'"
    }

    def createAppDeployProcessStep(projectName, applicationName, serviceName) {
        def processName = 'Deploy'
        def processStepName = "deployService-${serviceName}"
        createAppProcessStep(projectName, applicationName, processName, [
                processStepName: processStepName,
                processStepType: 'service', subservice: serviceName]
        )
        logger INFO, "Process step ${processStepName} has been created for process ${processName} in service ${serviceName}"
    }

    def createOrUpdateMapping(projName, envProjName, envName, clusterName, serviceName, service, applicationName = null) {
        assert envProjName
        assert envName
        assert clusterName

        def mapping = service.serviceMapping

        def existingMap = getExistingMapping(projName, serviceName, envProjName, envName, applicationName)

        def envMapName
        if (existingMap && !applicationName) {
            logger INFO, "Environment map already exists for service ${serviceName} and cluster ${clusterName}"
            envMapName = existingMap.environmentMapName
        }
        else if(existingMap && applicationName){
            logger INFO, "Environment map already exists for service ${serviceName} in application ${applicationName} and cluster ${clusterName}"
            envMapName = existingMap.tierMapName
        }
        else if (applicationName){
            def payload = [
                    environmentProjectName: envProjName,
                    environmentName: envName,
                    tierMapName: "${applicationName}-${envName}",
                    description: CREATED_DESCRIPTION,
            ]

            def result = createTierMap(projName, applicationName, payload)
            envMapName = result.tierMap?.tierMapName
        }
        else {
            def payload = [
                environmentProjectName: envProjName,
                environmentName: envName,
                description: CREATED_DESCRIPTION,
            ]

            def result = createEnvMap(projName, serviceName, payload)
            envMapName = result.environmentMap?.environmentMapName
        }

        assert envMapName

        def existingClusterMapping = existingMap?.serviceClusterMappings?.serviceClusterMapping?.find {
            it.clusterName == clusterName
            it.serviceName == serviceName
        }

        def serviceClusterMappingName
        if (existingClusterMapping) {
            logger INFO, "Cluster mapping already exists"
            serviceClusterMappingName = existingClusterMapping.serviceClusterMappingName
        }
        else {
            def payload = [
                clusterName: clusterName,
                environmentName: envName,
                environmentProjectName: envProjName
            ]
            if (mapping) {
                def actualParameters = []
                mapping.each {k, v ->
                    if (v) {
                        actualParameters.add([actualParameterName: k, value: v])
                    }
                }
                payload.actualParameter = actualParameters
            }
            def result
            if (applicationName){
                payload.serviceName = "${serviceName}"
                payload.serviceClusterMappingName = "${clusterName}-${serviceName}"
                result = createAppServiceClusterMapping(projName, applicationName, envMapName, payload)
                logger INFO, "Created Service Cluster Mapping for ${serviceName} in application ${applicationName} and ${clusterName}"
                serviceClusterMappingName = result.serviceClusterMapping.serviceClusterMappingName
            }
            else{
                result = createServiceClusterMapping(projName, serviceName, envMapName, payload)
                logger INFO, "Created Service Cluster Mapping for ${serviceName} and ${clusterName}"
                serviceClusterMappingName = result.serviceClusterMapping.serviceClusterMappingName
            }
        }

        assert serviceClusterMappingName
        service.containers?.each { container ->
            def payload = [
                containerName: container.container.containerName
            ]
            if (container.mapping) {
                def actualParameters = []
                container.mapping.each {k, v ->
                    if (v) {
                        actualParameters.add(
                            [actualParameterName: k, value: v]
                        )
                    }
                }
                payload.actualParameter = actualParameters
            }
            createServiceMapDetails(
                projName,
                serviceName,
                envMapName,
                serviceClusterMappingName,
                payload,
                applicationName
            )
        }
    }

    def getExistingTierMap(applicationName, envName, envProjectName, projectName){
        def tierMaps = getTierMaps(projectName, applicationName)
        def existingTierMap = tierMaps?.find {
            it.applicationName == applicationName && it.environmentName == envName && it.environmentProjectName == envProjectName && it.projectName == projectName
        }
        existingTierMap
    }

    def getExistingEnvMap(projectName, serviceName, envProjectName, envName){
        def envMaps
        envMaps = getEnvMaps(projectName, serviceName)
        def existingMap = envMaps.environmentMap?.find {
            it.environmentProjectName == envProjectName && it.projectName == projectName && it.serviceName == serviceName && it.environmentName == envName
        }
        existingMap
    }

    def getExistingMapping(projectName, serviceName, envProjectName, envName, applicationName = null) {
        def existingServiceMapping
        if (applicationName){
            existingServiceMapping = getExistingTierMap(applicationName, envName, envProjectName, projectName)
        }
        else{
            existingServiceMapping = getExistingEnvMap(projectName, serviceName, envProjectName, envName)
        }
        existingServiceMapping
    }

    def isBoundPort(containerPort, servicePortMeta) {
        if (containerPort.portName == servicePortMeta.portName) {
            return true
        }
        if (servicePortMeta.targetPort =~ /^\d+$/ && servicePortMeta.targetPort == containerPort.containerPort) {
            return true
        }
        return false
    }

    def mapContainerPorts(projectName, serviceName, container, service, applicationName = null) {
        container.ports?.each { containerPort ->
            service.portsMeta?.each { servicePortMeta ->
                if (isBoundPort(containerPort, servicePortMeta)) {
                    def portName = servicePortMeta.portNameRaw ? servicePortMeta.portNameRaw : "servicehttp${serviceName}${container.container.containerName}${containerPort.containerPort}"
                    def generatedPort = [
                        portName: portName,
                        listenerPort: servicePortMeta.listenerPort,
                        subcontainer: container.container.containerName,
                        subport: containerPort.portName
                    ]
                    createPort(projectName, serviceName, generatedPort, null, false, applicationName)
                    logger INFO, "Port ${portName} has been created for service ${serviceName}, listener port: ${generatedPort.listenerPort}, container port: ${generatedPort.subport}"
                }
            }
        }
    }

    def createOrUpdateContainer(projectName, serviceName, container, applicationName = null) {
        logger DEBUG, "Container payload:"
        logger DEBUG, new JsonBuilder(container).toPrettyString()

        def containerName = container.container.containerName
        assert containerName
        logger INFO, "Going to create container ${serviceName}/${containerName}"
        logger INFO, pretty(container.container)

        createContainer(projectName, serviceName, container.container, applicationName)
        logger INFO, "Container ${serviceName}/${containerName} has been created"
        discoveredSummary[serviceName] = discoveredSummary[serviceName] ?: [:]
        discoveredSummary[serviceName][containerName] = [:]

        container.ports.each { port ->
            createPort(projectName, serviceName, port, containerName, false, applicationName)
            logger INFO, "Port ${port.portName} has been created for container ${containerName}, container port: ${port.containerPort}"
        }

        if (container.env) {
            container.env.each { env ->
                createEnvironmentVariable(projectName, serviceName, containerName, env, false, applicationName)
                logger INFO, "Environment variable ${env.environmentVariableName} has been created"
            }
        }

    }

    def buildSecretsDefinition(namespace, secrets) {
        def retval = []
        secrets.each {
            def name = it.name
            def secret = kubeClient.getSecret(name, clusterEndpoint, namespace, accessToken)

            def dockercfg = secret.data['.dockercfg']
            if (dockercfg) {
                def decoded = new JsonSlurper().parseText(new String(dockercfg.decodeBase64(), "UTF-8"))

                if (decoded.keySet().size() == 1) {
                    def repoUrl = decoded.keySet().first()
                    def username = decoded[repoUrl].username
                    def password = decoded[repoUrl].password

                    // Password may be absent
                    // In this case we can do nothing

                    if (password) {
                        def cred = [
                            repoUrl: repoUrl,
                            userName: username,
                            password: password
                        ]
                        retval.add(cred)
                    }
                    else {
                        logger WARNING, "Cannot retrieve password from secret for $repoUrl, please create a credential manually"
                    }
                }
            }
        }
        retval
    }

    def buildServiceDefinition(kubeService, deployment){

        def logService = []
        def logDeployment = []
        def serviceName = kubeService.metadata.name
        def deployName = deployment.metadata.name

        logService.push("/kind".trim())
        logService.push("/apiVersion".trim())
        logService.push("/metadata/name".trim())
        kubeService.spec.selector.each{key, value->
            logService.push("/spec/selector/${key}".trim())
        }

        logDeployment.push("/apiVersion".trim())
        logDeployment.push("/kind".trim())
        logDeployment.push("/metadata/name".trim())
        deployment.metadata.labels.each{key, value ->
            logDeployment.push("/metadata/labels/${key}".trim())
        }

        def efServiceName
        if (serviceName =~ /(?i)${deployName}/) {
            efServiceName = serviceName
        }
        else {
            efServiceName = "${serviceName}-${deployName}"
        }
        def efService = [
            service: [
                serviceName: efServiceName
            ],
            serviceMapping: [:]
        ]

        // Service Fields
        def defaultCapacity = deployment.spec?.replicas ?: 1
        logDeployment.push("/spec/replicas")
        efService.service.defaultCapacity = defaultCapacity

        if (deployment.spec?.strategy?.rollingUpdate) {
            def rollingUpdate = deployment.spec.strategy.rollingUpdate

            logDeployment.push("/spec/strategy/rollingUpdate/maxSurge")

            if (rollingUpdate.maxSurge =~ /%/) {

                efService.serviceMapping.with {
                    deploymentStrategy = 'rollingDeployment'
                    maxRunningPercentage = getMaxRunningPercentage(rollingUpdate.maxSurge)
                }
            }
            else {
                efService.service.maxCapacity = getMaxCapacity(defaultCapacity, rollingUpdate.maxSurge)
            }

            logDeployment.push("/spec/strategy/rollingUpdate/maxUnavailable")
            if (rollingUpdate.maxUnavailable =~ /%/) {
                efService.serviceMapping.with {
                    minAvailabilityPercentage = getMinAvailabilityPercentage(rollingUpdate.maxUnavailable)
                    deploymentStrategy = 'rollingDeployment'
                }
            }
            else {
                efService.service.minCapacity = getMinCapacity(defaultCapacity, rollingUpdate.maxUnavailable)
            }

        }
        logService.push("/spec/clusterIP")
        logService.push("/spec/type")
        logService.push("/spec/sessionAffinity")
        logService.push("/spec/loadBalancerSourceRanges")

        def mapping = buildServiceMapping(kubeService)
        mapping.each { k, v ->
            efService.serviceMapping[k] = v;
        }

        // Ports
        def portInd = 0
        efService.portsMeta = kubeService.spec?.ports?.collect { port ->
            def name
            if (port.targetPort) {
                name = port.targetPort as String
            }
            else {
                name = "${port.protocol}${port.port}"
            }
            logService.push("/spec/ports[${portInd}]/name")
            logService.push("/spec/ports[${portInd}]/protocol")
            logService.push("/spec/ports[${portInd}]/port")
            logService.push("/spec/ports[${portInd}]/targetPort")
            portInd += 1
            [portName: name.toLowerCase(), listenerPort: port.port, targetPort: port.targetPort, portNameRaw: port.name]
        }

        // Containers
        def containers = deployment.spec.template.spec.containers
        containers.eachWithIndex{ kubeContainer, index ->
            logDeployment.push("/spec/template/spec/containers[${index}]/name")
            logDeployment.push("/spec/template/spec/containers[${index}]/image")
            kubeContainer?.command.eachWithIndex{ singleCommand, ind ->
                logDeployment.push("/spec/template/spec/containers[${index}]/command[${ind}]")
            }
            kubeContainer?.args.eachWithIndex{ arg, ind ->
                logDeployment.push("/spec/template/spec/containers[${index}]/args[${ind}]")
            }
            logDeployment.push("/spec/template/spec/containers[${index}]/resources/limits/memory")
            logDeployment.push("/spec/template/spec/containers[${index}]/resources/limits/cpu")
            logDeployment.push("/spec/template/spec/containers[${index}]/resources/requests/memory")
            logDeployment.push("/spec/template/spec/containers[${index}]/resources/requests/cpu")
            kubeContainer?.ports.eachWithIndex{ port, ind ->
                logDeployment.push("/spec/template/spec/containers[${index}]/ports[${ind}]/containerPort")
            }
            kubeContainer?.volumeMounts.eachWithIndex{ volume, ind ->
                logDeployment.push("/spec/template/spec/containers[${index}]/volumeMounts[${ind}]/name")
                logDeployment.push("/spec/template/spec/containers[${index}]/volumeMounts[${ind}]/mountPath")
            }
            kubeContainer?.env.eachWithIndex{ singleEnv, ind ->
                logDeployment.push("/spec/template/spec/containers[${index}]/env[${ind}]/name")
                logDeployment.push("/spec/template/spec/containers[${index}]/env[${ind}]/value")
            }

        }
        efService.containers = containers.collect { kubeContainer ->
            def container = buildContainerDefinition(kubeContainer)//, logDeployment)
            container
        }


        // Volumes
        if (deployment.spec.template.spec.volumes) {
            def index = 0
            def volumes = deployment.spec.template.spec.volumes.collect { volume ->
                def retval = [name: volume.name]
                logDeployment.push("/spec/template/spec/volumes[${index}]/name")
                if (volume.hostPath?.path) {
                    retval.hostPath = volume.hostPath.path
                    logDeployment.push("/spec/template/spec/volumes[${index}]/hostPath/path")
                }
                index += 1
                retval
            }
            efService.service.volume = new JsonBuilder(volumes).toString()
        }

        def flatService = flattenMap('', kubeService, [:])
        def flatDeployment = flattenMap('', deployment, [:])

// def ignoreList = []
        flatService.each{ key, value ->
            if (!listContains(logService, key)){
// logger WARNING, "Ignored items ${key} = ${value} from Service '${kubeService.metadata.name}'!"
// ignoreList.push("Ignored items ${key} = ${value} from Service '${kubeService.metadata.name}'!")
                ignoreList.push([type: "Service", name: "${kubeService.metadata.name}", field: "${key} = ${value}"])

            }

        }
        flatDeployment.each{ key, value ->
            if (!listContains(logDeployment, key)){
// logger WARNING, "Ignored items ${key} = ${value} from Deployment '${deployment.metadata.name}'!"
// ignoreList.push("Ignored items ${key} = ${value} from Deployment '${deployment.metadata.name}'!")
                ignoreList.push([type: "Deployment", name: "${deployment.metadata.name}", field: "${key} = ${value}"])
            }
        }

        efService
    }

    def listContains (list, key){
        def bool = false
        list.each{item->
            if (item.compareTo(key) == 0){
                bool = true
            }
        }
        bool
    }

// This one can be redefined for OpenShift
    def buildServiceMapping(kubeService) {
        def mapping = [:]
        mapping.loadBalancerIP = kubeService.spec?.loadBalancerIP
        mapping.serviceType = kubeService.spec?.type
        mapping.sessionAffinity = kubeService.spec?.sessionAffinity
        def sourceRanges = kubeService.spec?.loadBalancerSourceRanges?.join(',')

        mapping.loadBalancerSourceRanges = sourceRanges
        // Not here, is's from kube
        // if (namespace != 'default') {
        // mapping.namespace = namespace
        // }

        // Routes
        def serviceName = getKubeServiceName(kubeService)

        def route
        // One route per service for us
        // OpenShift allows more than one route
        parsedConfigList.each { object ->
            if (object.kind == KIND_ROUTE && object.spec?.to?.kind == KIND_SERVICE && object.spec?.to?.name == serviceName) {
                if (route) {
                    def routeName = object.metadata?.name
                    logger WARNING, "Only one route per service is allowed in ElectricFlow. The route ${routeName} will not be added."
                }
                else {
                    route = object
                }
            }
        }

        if (route) {
            mapping.routeName = route.metadata?.name
            mapping.routeHostname = route.spec?.host
            mapping.routePath = route.spec?.path
            mapping.routeTargetPort = route.spec?.port?.targetPort
        }

        return mapping
    }

    def updateEFService(efService, kubeService) {
        def payload = kubeService.service
        def projName = efService.projectName
        def serviceName = efService.serviceName
        payload.description = efService.description ?: "Updated by EF Import Microservices"
        def result = updateService(projName, serviceName, payload)
        result
    }

    def createEFService(projectName, service, appName = null) {
        def payload = service.service
        payload.addDeployProcess = true
        payload.description = "Created by EF Import Microservices"
        def result = createService(projectName, payload, appName)
        result
    }

    def equalNames(String oneName, String anotherName) {
        assert oneName
        assert anotherName
        def normalizer = { name ->
            name = name.toLowerCase()
            name = name.replaceAll('-', '.')
        }
        return normalizer(oneName) == normalizer(anotherName)
    }

    def getMaxCapacity(defaultCapacity, maxSurge) {
        assert defaultCapacity
        if (maxSurge > 1) {
            return defaultCapacity + maxSurge
        }
        else {
            return null
        }
    }

    def getMinCapacity(defaultCapacity, maxUnavailable) {
        assert defaultCapacity
        if (maxUnavailable > 1) {
            return defaultCapacity - maxUnavailable
        }
        else {
            return null
        }
    }

    def getMinAvailabilityPercentage(percentage) {
        percentage = percentage.replaceAll('%', '').toInteger()
        return 100 - percentage
    }

    def getMaxRunningPercentage(percentage) {
        percentage = percentage.replaceAll('%', '').toInteger()
        return 100 + percentage
    }

    def parseImage(image) {
        // examples:
        // 'some.domain.name/repository-name:image-tag' = registry / repository name : tag
        // 'localhost:5000/some-namespace/repository-name:image-tag' = registry / repository namespace / repository name : tag
        // fyi... registry cannot contain extra URI path according to Docker limitations

        def parts = image.split('/')

        def optionalRegistry
        def optionalNamespace
        def repository
        def optionalTag

        def repositoryAndOptionalTag = parts.last()

        def repositoryAndOptionalTagSplitted = repositoryAndOptionalTag.split(':')
        repository = repositoryAndOptionalTagSplitted.first()
        if (repositoryAndOptionalTagSplitted.size() > 1) {
            optionalTag = repositoryAndOptionalTagSplitted.last()
        } else {
            optionalTag = 'latest'
        }

        if (parts.size() == 2) {
            if (parts.first() =~ /[:.]/) {
                // if first part contains ':' or '.' then it is considered as registry
                // e.g. 'some.domain.name' from 'some.domain.name/repository-name:image-tag'
                // e.g. 'localhost:5000' from 'localhost:5000/repository-name'
                optionalRegistry = parts.first()
            } else {
                // if first part does not contain ':' or '.' then it is considered as repository namespace
                // e.g. 'username' from 'username/repository-name:image-tag'
                // e.g. 'organization-namespace' from 'organization-namespace/repository-name'
                optionalNamespace = parts.first()
            }
        } else if (parts.size() > 2) {
            // e.g. 'some.domain.name/some-namespace/repository-name:image-tag'
            optionalRegistry = parts.first()
            optionalNamespace = parts[1..parts.size() - 2].join('/')
        }

        def repositoryWithOptionalNamespace
        if (optionalNamespace) {
            repositoryWithOptionalNamespace = optionalNamespace + '/' + repository
        } else {
            repositoryWithOptionalNamespace = repository
        }

        return [imageName: repositoryWithOptionalNamespace, version: optionalTag, repoName: optionalNamespace, registry: optionalRegistry]
    }

    def getImageName(image) {
        parseImage(image).imageName
    }

    def getRepositoryURL(image) {
        parseImage(image).repoName
    }

    def getImageVersion(image) {
        parseImage(image).version
    }

    def getRegistryUri(image) {
        parseImage(image).registry
    }

    def buildContainerDefinition(kubeContainer){//, logDeployment, index) {
        def container = [
            container: [
                containerName: kubeContainer.name,
                imageName: getImageName(kubeContainer.image),
                imageVersion: getImageVersion(kubeContainer.image),
                registryUri: getRegistryUri(kubeContainer.image) ?: null
            ]
        ]

        container.env = kubeContainer.env?.collect {
            [environmentVariableName: it.name, value: it.value]
        }

        if (kubeContainer.command) {
            def entryPoint = kubeContainer.command.join(',')
            container.container.entryPoint = entryPoint
        }
        if (kubeContainer.args) {
            def args = kubeContainer.args.join(',')
            container.container.command = args
        }
        // Ports
        if (kubeContainer.ports) {
            container.ports = kubeContainer.ports.collect { port ->
                def name

                if (port.name) {
                    name = port.name
                }
                else {
                    name = "${port.protocol}${port.containerPort}"
                }
                [portName: name.toLowerCase(), containerPort: port.containerPort]
            }
        }

        container.mapping = [:]

        // Liveness probe
        if (kubeContainer.livenessProbe) {
            def processedLivenessFields = []
            def probe = kubeContainer.livenessProbe.httpGet
            processedLivenessFields << 'httpGet'
            container.mapping.with {
                livenessHttpProbePath = probe?.path
                livenessHttpProbePort = probe?.port
                livenessInitialDelay = kubeContainer.livenessProbe?.initialDelaySeconds
                livenessPeriod = kubeContainer.livenessProbe?.periodSeconds
                processedLivenessFields << 'initialDelaySeconds'
                processedLivenessFields << 'periodSeconds'
                if (probe.httpHeaders?.size() > 1) {
                    logger WARNING, 'Only one liveness header is supported, will take the first'
                }
                def header = probe?.httpHeaders?.first()
                livenessHttpProbeHttpHeaderName = header?.name
                livenessHttpProbeHttpHeaderValue = header?.value
            }
            kubeContainer.livenessProbe?.each { k, v ->
                if (!(k in processedLivenessFields) && v) {
                    logger WARNING, "Field ${k} from livenessProbe is not supported"
                }
            }
        }
        // Readiness probe
        if (kubeContainer.readinessProbe) {
            def processedFields = ['command']
            container.mapping.with {
                def command = kubeContainer.readinessProbe.exec?.command
                readinessCommand = command?.first()
                if (command?.size() > 1) {
                    logger WARNING, 'Only one readiness command is supported'
                }
                processedFields << 'initialDelaySeconds'
                processedFields << 'periodSeconds'
                readinessInitialDelay = kubeContainer.readinessProbe?.initialDelaySeconds
                readinessPeriod = kubeContainer.readinessProbe?.periodSeconds
            }

            kubeContainer.readinessProbe?.each { k, v ->
                if (!(k in processedFields) && v) {
                    logger WARNING, "Field ${k} is from readinessProbe not supported"
                }
            }
        }
        def resources = kubeContainer.resources
        container.container.cpuCount = parseCPU(resources?.requests?.cpu)
        container.container.memorySize = parseMemory(resources?.requests?.memory)
        container.container.cpuLimit = parseCPU(resources?.limits?.cpu)
        container.container.memoryLimit = parseMemory(resources?.limits?.memory)

        // Volume mounts
        def mounts = kubeContainer.volumeMounts?.collect { vm ->
            def retval = [name: vm.name]
            if (vm.mountPath) {
                retval.mountPath = vm.mountPath
            }
            retval
        }
        if (mounts) {
            container.container.volumeMount = new JsonBuilder(mounts).toString()
        }

        container
    }

    def isSystemService(service) {
        def name = service.metadata.name
        name == 'kubernetes'
    }

    def parseCPU(cpuString) {
        if (!cpuString) {
            return
        }
        if (cpuString =~ /m/) {
            def miliCpu = cpuString.replace('m', '') as int
            def cpu = miliCpu.toFloat() / 1000
            return cpu
        }
        else {
            return cpuString.toFloat()
        }
    }

    def parseMemory(memoryString) {
        if (!memoryString) {
            return
        }
        if (!(memoryString instanceof String)){
            memoryString = memoryString.toString()
        }
        // E, P, T, G, M, K
        def memoryNumber = memoryString.replaceAll(/\D+/, '')
        def suffix = memoryString.replaceAll(/\d+/, '')
        def power
        ['k', 'm', 'g', 't', 'p', 'e'].eachWithIndex { elem, index ->
            if (suffix =~ /(?i)${elem}/) {;
                power = index - 1
                // We store memory in MB, therefore KB will be power -1, mb will be the power of 1 and so on
            }
        }
        if (power) {
            def retval = memoryNumber.toInteger() * (1024 ** power)
            return Math.ceil(retval).toInteger()
        }
        else {
            return memoryNumber.toInteger()
        }
    }

    def getCredName(cred) {
        "${cred.repoUrl} - ${cred.userName}"
    }

    def prettyPrint(object) {
        println new JsonBuilder(object).toPrettyString()
    }

    def pretty(o) {
        new JsonBuilder(o).toPrettyString()
    }

    def publishReport(ignoreList) {
        def text = renderIgnoredFieldsReport(ignoreList, REPORT_TEMPLATE)

        def dir = new File('artifacts').mkdir()
        def random = new Random()
        def randomSuffix = random.nextInt(10 ** 5)

        def reportFilename = "OpenShift_IgnoreList_${randomSuffix}.html"
        def report = new File("artifacts/${reportFilename}")
        report.write(text)
        String jobStepId = System.getenv('COMMANDER_JOBSTEPID')

        def reportName = "OpenShift Ignored Fields Report"
        publishLink(reportName, "/commander/jobSteps/${jobStepId}/${reportFilename}")
    }

    def renderIgnoredFieldsReport(tableText, String template) {
        def engine = new groovy.text.SimpleTemplateEngine()
        def templateParams = [:]

        templateParams.tableText = tableText
        def text = engine.createTemplate(template).make(templateParams).toString()
        text
    }

    def publishLink(String name, String link) {
        setEFProperty("${REPORT_URL_PROPERTY}${name}", link)
        try {
            setEFProperty("/myJob/report-urls/${name}", link)
        }
        catch (Throwable e) {
            logger ERROR, "Issues while setting property cause ${e} !"
        }
        logger INFO, "Some fields have not been imported. Full list of ignored fields available in the report on the link: ${link} !"
    }

    def buildReportText(list){
        def writer = new StringWriter()
        def markup = new groovy.xml.MarkupBuilder(writer)
        markup.html{
            list.each{ item ->
                tr {
                    td(class:"text-center", item.type)
                    td(class:"text-center", item.name)
                    td(class:"text-center", item.field)
                }
            }
        }
        writer.toString()
    }

    def getKubeServiceName(kubeService) {
        def name = kubeService.metadata?.name
        assert name
        return name
    }

}

public class ImportBuildFromTemplate extends EFClient {
    Yaml parser = new Yaml()
    def parsedConfigList = []

    def importBuildFromTemplate(fileYAML){
        def efServices = []
        def configList = fileYAML

        def parsedConfig = parser.load(configList)

        parsedConfig.objects.each{ obj ->
            parsedConfigList.push(obj)
        }

        logger INFO, "Parsed template strings:\n${fileYAML}\n"

        /*
        //Get ImageStrem Definitions
        def imageStreams
        try {
            imageStreams = getParsedImageStreams(parsedConfigList)
        }
        catch(Exception e) {
            println "Failed to find any imagestreams in the YAML file. Cause: ${e.message}"
            System.exit(-1)
        }
        logger INFO, "Parsed imagestreams yaml: \n${imageStreams}\n"
        */

        //Get BuildConfig Definitions
        def buildConfigs
        try {
            buildConfigs = getParsedBuildConfig(parsedConfigList)
        }
        catch(Exception e) {
            println "Failed to find any buildConfigs in the YAML file. Cause: ${e.message}"
            System.exit(-1)
        }

        logger INFO, "Parsed buildConfigs yaml: \n${buildConfigs}\n"

        buildConfigs
    }

    def resolveTemplateByParameters(template,templateValues) {
        def parametersMap = [:]
        if (templateValues) {
            templateValues = templateValues.trim()
            def values = templateValues.split(/\s*,\s*/)
            values.each { parameterAndValue ->
                if (parameterAndValue.contains('=')) {
                    String[] parameterAndValueSplitted = parameterAndValue.split(/\s*=\s*/)
                    parametersMap.put(parameterAndValueSplitted[0].trim(), parameterAndValueSplitted[1].trim())
                }
            }
        }
 
        def parametersMapCloned = parametersMap.clone();
       
        Yaml parser = new Yaml()
        def parsedTemplate = parser.load(template)
        def parametersWithDefaultValuesFromTemplate = [:]
        parsedTemplate.parameters.each { item ->
            if (item.containsKey('value')) {
                parametersWithDefaultValuesFromTemplate.put(item.name, item.value)
            }

        }
        
        if(parametersWithDefaultValuesFromTemplate) {
            parametersWithDefaultValuesFromTemplate.each { parameterName, parameterValue ->
                if (!parametersMapCloned.containsKey(parameterName)) {
                    logger INFO, "Parameter '$parameterName' is not provided, using its default value from template: '$parameterValue'"
                    parametersMapCloned.put(parameterName, parameterValue)
                }
            }
        }
        
        def resolvedTemplate = template
        if(parametersMapCloned) {
            parametersMapCloned.each { parameterName, parameterValue ->
                resolvedTemplate = resolvedTemplate.replaceAll(/\$\{{1,2}\s*${parameterName}\s*\}{1,2}/, parameterValue)
            }
        }

        return resolvedTemplate
    }

    def getParsedImageStreams(parsedConfigList){
        def imageStreams = []
        parsedConfigList.each { config ->
            if (config.kind == "ImageStream"){
                imageStreams.push(config)
            }
        }
        imageStreams
    }

    def getParsedBuildConfig(parsedConfigList){
        def buildConfigs = []
        parsedConfigList.each { config ->
            if (config.kind == "BuildConfig") {
                if (!isSystemBuildConfig(config)) {
                    buildConfigs.push(config)
                }
            }
        }
        buildConfigs
    }

    def isSystemBuildConfig(buildConfig) {
        def name = buildConfig.metadata.name
        name == 'kubernetes'
    }
}

public class OpenShiftBuildClient extends KubernetesClient {

    def createOrUpdateBuildConfig(String clusterEndpoint, String namespace, def buildConfigMap, String accessToken) {
        def buildConfigName = buildConfigMap.metadata.name
        if (!buildConfigName) {
            return null
        }

        logger INFO, "BuildConfigName: \n${buildConfigName}\n"

        // POST /oapi/v1/buildconfigs HTTP/1.1
        def response = doHttpGet(clusterEndpoint, "/oapi/v1/namespaces/${namespace}/buildconfigs/${buildConfigName}",accessToken, false)

        if (response.status == 200) {
            logger INFO, "BuildConfig $buildConfigName found in $namespace, updating buildConfig ..."
            createOrUpdateBuildConfig(response.data, buildConfigName, clusterEndpoint, namespace, buildConfigMap, accessToken)
        } else if (response.status == 404){
            logger INFO, "BuildConfig $buildConfigName does not exist in $namespace, creating route ..."
            createOrUpdateBuildConfig(null, buildConfigName, clusterEndpoint, namespace, buildConfigMap, accessToken)
        } else {
            handleError("Route check failed. ${response.statusLine}")
        }
    }
 
    def createOrUpdateBuildConfig(def existingRoute, String buildConfigName, String clusterEndpoint, String namespace, def buildConfigMap, String accessToken) {

        def result = new JsonBuilder(buildConfigMap).toPrettyString()
        logger INFO, "BuildConfig: \n${result.toPrettyString()}\n"
/*
        def payload = existingRoute
        if (payload) {
            payload = mergeObjs(payload, result)
        } else {
            payload = result
        }

        def createBuildConfig = existingRoute == null
        doHttpRequest(createBuildConfig ? POST : PUT,
                clusterEndpoint,
                createBuildConfig?
                        "/oapi/v1/namespaces/${namespace}/buildconfigs" :
                        "/oapi/v1/namespaces/${namespace}/buildconfigs/${buildConfigName}",
                ['Authorization' : accessToken], true,payload.toPrettyString())
*/
    }
}

EFClient efClient = new EFClient()
OpenShiftBuildClient buildClient = new OpenShiftBuildClient()

// Input parameters
def osTemplateYaml = '''apiVersion: v1
kind: Template
labels:
  template: eap64-basic-s2i
  xpaas: 1.3.2
metadata:
  annotations:
    description: Application template for EAP 6 applications built using S2I.
    iconClass: icon-jboss
    tags: eap,javaee,java,jboss,xpaas
    version: 1.3.2
  creationTimestamp: null
  name: eap64-basic-s2i
objects:
- apiVersion: v1
  kind: Service
  metadata:
    annotations:
      description: The web server's http port.
    labels:
      application: ${APPLICATION_NAME}
    name: ${APPLICATION_NAME}
  spec:
    ports:
    - port: 8080
      targetPort: 8080
    selector:
      deploymentConfig: ${APPLICATION_NAME}
- apiVersion: v1
  id: ${APPLICATION_NAME}-http
  kind: Route
  metadata:
    annotations:
      description: Route for application's http service.
    labels:
      application: ${APPLICATION_NAME}
    name: ${APPLICATION_NAME}
  spec:
    host: ${HOSTNAME_HTTP}
    to:
      name: ${APPLICATION_NAME}
- apiVersion: v1
  kind: ImageStream
  metadata:
    labels:
      application: ${APPLICATION_NAME}
    name: ${APPLICATION_NAME}
- apiVersion: v1
  kind: BuildConfig
  metadata:
    labels:
      application: ${APPLICATION_NAME}
    name: ${APPLICATION_NAME}
  spec:
    output:
      to:
        kind: ImageStreamTag
        name: ${APPLICATION_NAME}:latest
    source:
      contextDir: ${CONTEXT_DIR}
      git:
        ref: ${SOURCE_REPOSITORY_REF}
        uri: ${SOURCE_REPOSITORY_URL}
      type: Git
    strategy:
      sourceStrategy:
        forcePull: true
        from:
          kind: ImageStreamTag
          name: jboss-eap64-openshift:1.4
          namespace: ${IMAGE_STREAM_NAMESPACE}
      type: Source
    triggers:
    - github:
        secret: ${GITHUB_WEBHOOK_SECRET}
      type: GitHub
    - generic:
        secret: ${GENERIC_WEBHOOK_SECRET}
      type: Generic
    - imageChange: {}
      type: ImageChange
    - type: ConfigChange
- apiVersion: v1
  kind: DeploymentConfig
  metadata:
    labels:
      application: ${APPLICATION_NAME}
    name: ${APPLICATION_NAME}
  spec:
    replicas: 1
    selector:
      deploymentConfig: ${APPLICATION_NAME}
    strategy:
      type: Recreate
    template:
      metadata:
        labels:
          application: ${APPLICATION_NAME}
          deploymentConfig: ${APPLICATION_NAME}
        name: ${APPLICATION_NAME}
      spec:
        containers:
        - env:
          - name: OPENSHIFT_KUBE_PING_LABELS
            value: application=${APPLICATION_NAME}
          - name: OPENSHIFT_KUBE_PING_NAMESPACE
            valueFrom:
              fieldRef:
                fieldPath: metadata.namespace
          - name: HORNETQ_CLUSTER_PASSWORD
            value: ${HORNETQ_CLUSTER_PASSWORD}
          - name: HORNETQ_QUEUES
            value: ${HORNETQ_QUEUES}
          - name: HORNETQ_TOPICS
            value: ${HORNETQ_TOPICS}
          - name: JGROUPS_CLUSTER_PASSWORD
            value: ${JGROUPS_CLUSTER_PASSWORD}
          - name: AUTO_DEPLOY_EXPLODED
            value: ${AUTO_DEPLOY_EXPLODED}
          image: ${APPLICATION_NAME}
          imagePullPolicy: Always
          livenessProbe:
            exec:
              command:
              - /bin/bash
              - -c
              - /opt/eap/bin/livenessProbe.sh
          name: ${APPLICATION_NAME}
          ports:
          - containerPort: 8778
            name: jolokia
            protocol: TCP
          - containerPort: 8080
            name: http
            protocol: TCP
          - containerPort: 8888
            name: ping
            protocol: TCP
          readinessProbe:
            exec:
              command:
              - /bin/bash
              - -c
              - /opt/eap/bin/readinessProbe.sh
        terminationGracePeriodSeconds: 60
    triggers:
    - imageChangeParams:
        automatic: true
        containerNames:
        - ${APPLICATION_NAME}
        from:
          kind: ImageStreamTag
          name: ${APPLICATION_NAME}:latest
      type: ImageChange
    - type: ConfigChange
parameters:
- description: The name for the application.
  name: APPLICATION_NAME
  required: true
  value: eap-app
- description: 'Custom hostname for http service route. Leave blank for default hostname,
    e.g.: <application-name>-<project>.<default-domain-suffix>'
  name: HOSTNAME_HTTP
- description: Git source URI for application
  name: SOURCE_REPOSITORY_URL
  required: true
  value: https://github.com/jboss-developer/jboss-eap-quickstarts
- description: Git branch/tag reference
  name: SOURCE_REPOSITORY_REF
  value: 6.4.x
- description: Path within Git project to build; empty for root project directory.
  name: CONTEXT_DIR
  value: kitchensink
- description: Queue names
  name: HORNETQ_QUEUES
- description: Topic names
  name: HORNETQ_TOPICS
- description: HornetQ cluster admin password
  from: '[a-zA-Z0-9]{8}'
  generate: expression
  name: HORNETQ_CLUSTER_PASSWORD
  required: true
- description: GitHub trigger secret
  from: '[a-zA-Z0-9]{8}'
  generate: expression
  name: GITHUB_WEBHOOK_SECRET
  required: true
- description: Generic build trigger secret
  from: '[a-zA-Z0-9]{8}'
  generate: expression
  name: GENERIC_WEBHOOK_SECRET
  required: true
- description: Namespace in which the ImageStreams for Red Hat Middleware images are
    installed. These ImageStreams are normally installed in the openshift namespace.
    You should only need to modify this if you've installed the ImageStreams in a
    different namespace/project.
  name: IMAGE_STREAM_NAMESPACE
  required: true
  value: openshift
- description: JGroups cluster password
  from: '[a-zA-Z0-9]{8}'
  generate: expression
  name: JGROUPS_CLUSTER_PASSWORD
  required: true
- description: Controls whether exploded deployment content should be automatically
    deployed
  name: AUTO_DEPLOY_EXPLODED
  value: "false"'''.trim()
def osTemplateValues = '''IMAGE_STREAM_NAMESPACE,AUTO_DEPLOY_EXPLODED=true,JGROUPS_CLUSTER_PASSWORD=abcd1234,IMAGE_STREAM_NAMESPACE=imageNamespace,GENERIC_WEBHOOK_SECRET=gwebh123,GITHUB_WEBHOOK_SECRET=gitwebh1,HORNETQ_CLUSTER_PASSWORD=hor12345,HORNETQ_TOPICS=hornetqt,HORNETQ_QUEUES=hornetqq,CONTEXT_DIR=kitchensink2,SOURCE_REPOSITORY_REF=6.4,SOURCE_REPOSITORY_URL=https://github.com/jboss-developer/jboss-eap-quickstarts,HOSTNAME_HTTP=myhost,APPLICATION_NAME=testApp'''.trim()
def projectName = '/plugins//project'
def envProjectName = ''
def environmentName = ''
def clusterName = ''
def applicationScoped = '0'
def applicationName = ''

 

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
    // If any of the environment parameters are specified then *all* of them must be specified.
    println "Either specify all the parameters required to identify the OpenShift-backed ElectricFlow cluster (environment project name, environment name, and cluster name) where the newly created microservice(s) will be deployed. Or do not specify any of the cluster related parameters in which case the service mapping to a cluster will not be created for the microservice(s)."
    System.exit(-1)
}

// Parse BuildConfig
def importFromTemplate = new ImportBuildFromTemplate()
def templateResolved = importFromTemplate.resolveTemplateByParameters(osTemplateYaml, osTemplateValues)
def buildConfigMap = importFromTemplate.importBuildFromTemplate(templateResolved)

//Create/Update BuildConfig

def pluginConfig = buildClient.getPluginConfig(efClient, clusterName, envProjectName, environmentName)

/*
def accessToken = buildClient.retrieveAccessToken (pluginConfig)
println "AccessToken: \n${accessToken}\n"

def clusterParameters = buildClient.getProvisionClusterParameters(
    clusterName,
    envProjectName,
    environmentName
)

def clusterEndpoint = pluginConfig.clusterEndpoint
println "ClusterEndpoint: \n${clusterEndpoint}\n"

def namespace = clusterParameters.project
println "Namespace: \n${namespace}\n"

buildClient.createOrUpdateBuildConfig(clusterEndpoint, namespace, buildConfigMap, accessToken)