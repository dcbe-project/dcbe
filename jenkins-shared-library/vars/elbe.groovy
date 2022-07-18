def call(String elbexml, Boolean buildsdk)
{
  def podlabel = "elbe-${elbexml}-${UUID.randomUUID().toString()}"

  properties([disableResume()])

  podTemplate(label       : "${podlabel}",
              podRetention: onFailure(),
              containers  : [containerTemplate(name                 : 'elbe',
                                               image                : "${env.DOCKER_REGISTRY}/elbe:latest",
                                               alwaysPullImage      : true,
                                               resourceRequestCpu   : '2000m',
                                               resourceRequestMemory: '16Gi',
                                               resourceLimitCpu     : '2000m',
                                               resourceLimitMemory  : '16Gi',
                                               ttyEnabled           : true,
                                               privileged           : true,
                                               command              : 'cat'),
                            ],
              envVars     : [containerEnvVar(key                    : 'HOME',
                                             value                  : '/home/jenkins/agent'),
                            ],
              volumes     : [persistentVolumeClaim(claimName        : 'vm-modules-pvc',
                                                   mountPath        : '/lib/modules'),
                             nfsVolume(serverAddress                : "${env.APT_REPO_NFS}",
                                       serverPath                   : '/share-rw',
                                       mountPath                    : '/repo'),

                            ],
             )
  {
    node ("${podlabel}") {
      stage('checkout') {
        checkout scm
      }
      container('elbe') {
        stage('build image') {
          sh "modprobe binfmt_misc"
          sh "update-binfmts --enable qemu-arm"
          sh "update-binfmts --enable qemu-aarch64"
          sh "elbe --version"
          sh "sed -i \"s/DCBE_REPO_IP/${env.APT_REPO_HTTP}/g\" ${elbexml}"
          sh "mv ${elbexml} ${elbexml}.in"
          sh "wget http://${env.APT_REPO_HTTP}/dcbe.gpg"
          sh "cat dcbe.gpg | gpg --enarmour | grep -v Comment > dcbe.key"
          sh "sed -i \"s/PGP ARMORED FILE/PGP PUBLIC KEY BLOCK/g\" dcbe.key"
          sh "awk '/DCBE_REPO_RAW_KEY/ { system ( \"cat dcbe.key\" ) } !/DCBE_REPO_RAW_KEY/ { print; }' ${elbexml}.in > ${elbexml}"
          sh "test -d archive && elbe chg_archive ${elbexml} archive || /bin/true"
          sh "elbe buildchroot -t image ${elbexml}"
        }
        if (buildsdk) {
          stage('build image') {
            sh "elbe buildsdk image"
          }
        }
        stage('upload images') {
          sh "rm -rf image/chroot image/target image/repo image/sysroot"
          String tf = "/repo/images/${elbexml}/${currentBuild.startTimeInMillis}"
          sh "mkdir -p ${tf}"
          sh "cp -a image/* ${tf}/"
        }
      }
    }
  }
}
