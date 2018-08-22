def repository = 'hydro-serving-gateway'

/*
def buildAndPublishReleaseFunction={
    //Buid serving
    def curVersion = getVersion()
    sh "sbt -DappVersion=${curVersion} compile docker"
}
*/

def buildFunction={
    //Buid serving
    def curVersion = getVersion()
    sh "sbt -DappVersion=${curVersion} compile docker"
}

def collectTestResults = {
    junit testResults: '**/target/test-reports/io.hydrosphere*.xml', allowEmptyResults: true
}

pipelineCommon(
        repository,
        false, //needSonarQualityGate,
        ["hydrosphere/serving-gateway"],
        collectTestResults,
        buildFunction,
        buildFunction,
        buildFunction
)