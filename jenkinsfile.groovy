def repository = 'hydro-serving-gateway'

def buildFunction={
    sh "sbt test docker:publishLocal"
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
        buildFunction,
        null,
        "",
        "",
        {},
        commitToCD("gateway")
)
