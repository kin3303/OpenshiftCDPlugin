public class OpenShiftClient extends KubernetesClient {

    def createOrUpdatePlatformSpecificResources(String clusterEndpoint, String namespace, def serviceDetails, String accessToken) {
        if (OFFLINE) return null
        createOrUpdateRoute(clusterEndpoint, namespace, serviceDetails, accessToken)
    }

    def getRoutes(String clusterEndpoint, String namespace, String accessToken) {
        println clusterEndpoint
        def response = doHttpGet(clusterEndpoint, "/apis/route.openshift.io/v1/namespaces/${namespace}/routes", accessToken, true)
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
                "/apis/route.openshift.io/v1/namespaces/${namespace}/routes/${routeName}",
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
                        "/apis/route.openshift.io/v1/namespaces/${namespace}/routes" :
                        "/apis/route.openshift.io/v1/namespaces/${namespace}/routes/${routeName}",
                ['Authorization' : accessToken],
                /*failOnErrorCode*/ true,
                payload)
    }

    String buildRoutePayload(String routeName, String routeHostname, String routePath, String routeTargetPort, def serviceDetails, def existingRoute) {
        def serviceName = getServiceNameToUseForDeployment(serviceDetails)
        def json = new JsonBuilder()
        def result = json{
            kind "Route"
            apiVersion "route.openshift.io/v1"
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
                "/apis/route.openshift.io/v1/namespaces/${namespace}/routes/${routeName}",
                accessToken, /*failOnErrorCode*/ false)

        if (response.status == 200){
            logger DEBUG, "Route $routeName found in $namespace"

            def existingRoute = response.data
            def serviceName = getServiceNameToUseForDeployment(serviceDetails)
            if (existingRoute?.spec?.to?.kind == 'Service' && existingRoute?.spec?.to?.name == serviceName) {
                logger DEBUG, "Deleting route $routeName in $namespace"

                doHttpRequest(DELETE,
                        clusterEndpoint,
                        "/apis/route.openshift.io/v1/namespaces/${namespace}/routes/${routeName}",
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
        def path = "/apis/apps.openshift.io/v1/namespaces/${namespace}/deploymentconfigs"
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
