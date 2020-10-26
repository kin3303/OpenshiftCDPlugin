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
    *  A way to check cluster is up/reachable. Currently simply hits API base endpoint
    *  In future there might be a "health" endpoint available which can be used
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
        def pluginProjectName = '$[/myProject/projectName]'
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
        handleError("Timed out waiting for value of '${responseField}' response field  ('${clusterEndpoint}/${resourceUri}') to attain '${expectedValue}'.")
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
                maxUnavailableValue =  args.minCapacity ?
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

    //------------------------------------------------------------------------------------------------------------------------------------------------------------------
    //  DUKIM-ADDED
    //------------------------------------------------------------------------------------------------------------------------------------------------------------------
     def getPropertyFromService(String projectName, String applicationName, String serviceName, String propertyName) { 
        def result=getEFProperty("/projects/${projectName}/applications/${applicationName}/services/${serviceName}/${propertyName}", /*ignoreError*/ true)
        def mapPayload = new JsonSlurper().parseText(new String(result.data.property.value))
        return mapPayload
    }
}
