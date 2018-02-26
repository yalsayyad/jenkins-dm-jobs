import groovy.transform.Field

node('jenkins-master') {
  if (params.WIPEOUT) {
    deleteDir()
  }

  dir('jenkins-dm-jobs') {
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs(),
      changelog: false,
      poll: false
    ])
    notify = load 'pipelines/lib/notify.groovy'
    util = load 'pipelines/lib/util.groovy'
  }
}

notify.wrap {
  def required = [
    'EUPS_TAG',
  ]

  util.requireParams(required)

  def eupsTag         = params.EUPS_TAG
  def docTemplateRepo = 'https://github.com/lsst/pipelines_lsst_io'
  def docTemplateRef  = 'tickets/DM-11216'

  def relImage   = "lsstsqre/centos:7-stack-lsst_distrib-${eupsTag}"
  def masonImage = 'lsstsqre/ltd-mason'

  def run = {
    def meerImage = null
    stage('install documenteer') {
      def config = util.dedent("""
        FROM    ${relImage}

        USER    root
        RUN     yum -y install graphviz && yum -y clean all

        USER    lsst
        RUN     . /opt/lsst/software/stack/loadLSST.bash && \
                  pip install --upgrade documenteer[pipelines]==0.2.6
      """)

      meerImage = "${relImage}-local"
      util.buildImage(config, meerImage)
    } // stage

    dir('pipelines_lsst_io') {
      stage('build docs') {
        deleteDir()

        git([
          url: docTemplateRepo,
          branch: docTemplateRef,
        ])

        util.insideWrap(meerImage) {
          util.bash '''
            . /opt/lsst/software/stack/loadLSST.bash

            setup -r .
            build-stack-docs -d . -v
          '''
        } // util.insideWrap
      } // stage

      stage('publish') {
        publishHTML([
          allowMissing: false,
          alwaysLinkToLastBuild: true,
          keepAll: true,
          reportDir: '_build/html',
          reportFiles: 'index.html',
          reportName: 'doc build',
          reportTitles: '',
        ])

        archiveArtifacts([
          artifacts: '_build/html/**/*',
          allowEmptyArchive: true,
          fingerprint: false,
        ])

        withEnv([
          "LTD_MASON_BUILD=true",
          "LTD_MASON_PRODUCT=pipelines",
          "LTD_KEEPER_URL=https://keeper.lsst.codes",
          "LTD_KEEPER_USER=travis",
          "TRAVIS_PULL_REQUEST=false",
          "TRAVIS_REPO_SLUG=lsst/pipelines_lsst_io",
          "TRAVIS_BRANCH=${eupsTag}",
        ]) {
          withCredentials([[
            $class: 'UsernamePasswordMultiBinding',
            credentialsId: 'ltd-mason-aws',
            usernameVariable: 'LTD_MASON_AWS_ID',
            passwordVariable: 'LTD_MASON_AWS_SECRET',
          ],
          [
            $class: 'UsernamePasswordMultiBinding',
            credentialsId: 'ltd-keeper',
            usernameVariable: 'LTD_KEEPER_USER',
            passwordVariable: 'LTD_KEEPER_PASSWORD',
          ]]) {
            docker.image(masonImage).inside {
              // expect that the service will return an HTTP 502, which causes
              // ltd-mason-travis to exit 1
              util.sh '''
              /usr/bin/ltd-mason-travis --html-dir _build/html --verbose || true
              '''
            } // util.insideWrap
          } // withCredentials
        } //withEnv
      } // stage
    } // dir
  } // run

  node('docker') {
    timeout(time: 30, unit: 'MINUTES') {
      run()
    }
  } // node
} // notify.wrap