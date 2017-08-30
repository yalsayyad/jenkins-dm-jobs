def notify = null

node('jenkins-master') {
  dir('jenkins-dm-jobs') {
    // XXX the git step seemed to blowup on a branch of '*/<foo>'
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs()
    ])
    notify = load 'pipelines/lib/notify.groovy'
    util = load 'pipelines/lib/util.groovy'
  }
}

try {
  notify.started()

  node('docker') {
    stage('pull') {
      docker.image('centos:6').pull()
      docker.image('centos:7').pull()
    }

    stage('build') {
      git([
        url: 'https://github.com/lsst-sqre/packer-newinstall.git',
        branch: 'master'
      ])

      def make = '''
        gem install --no-ri --no-rdoc bundler

        make
        bundle install
        bundle exec librarian-puppet install

        ./bin/packer build --only=docker-centos-6,docker-centos-7 centos_packerprep.json
      '''.stripIndent()

      rvm(make)
    }

    stage('push') {
      docker.withRegistry('https://index.docker.io/v1/', 'dockerhub-sqreadmin') {
        docker.image('lsstsqre/centos:6-packerprep').push()
        docker.image('lsstsqre/centos:7-packerprep').push()
      }
    }
  } // node
} catch (e) {
  // If there was an exception thrown, the build failed
  currentBuild.result = "FAILED"
  throw e
} finally {
  echo "result: ${currentBuild.result}"
  switch(currentBuild.result) {
    case null:
    case 'SUCCESS':
      notify.success()
      break
    case 'ABORTED':
      notify.aborted()
      break
    case 'FAILURE':
      notify.failure()
      break
    default:
      notify.failure()
  }
}

def rvm(String commands) {
  util.shColor "bash -c 'source /etc/profile.d/rvm.sh && rvm install 2.2 > /dev/null 2>&1'"
  util.shColor "bash -c 'source /etc/profile.d/rvm.sh && rvm use 2.2 && ${commands}'"
}
