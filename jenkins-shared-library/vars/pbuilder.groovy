def call(Map conf)
{
	properties([disableResume()])

	node
	{
		def pbuilds = conf.collectEntries {
			["${it.getKey()}-${it.getValue()}": pbuild(it.getKey(), it.getValue())]
		}

		parallel pbuilds
	}
}

def pbuild(String arch, String release)
{
	return {
		def podlabel = "pbuilder-${arch}-${release}-${UUID.randomUUID().toString()}"

		podTemplate(label       : "${podlabel}",
								podRetention: onFailure(),
								containers  : [containerTemplate(name                 : 'pbuilder',
																								 image                : "${env.DOCKER_REGISTRY}/cowbuilder-${arch}:${release}",
																								 alwaysPullImage      : true,
																								 resourceRequestCpu   : '8000m',
																								 resourceRequestMemory: '16Gi',
																								 resourceLimitCpu     : '16000m',
																								 resourceLimitMemory  : '16Gi',
																								 ttyEnabled           : true,
																								 privileged           : true,
																								 runAsUser            : '0',
																								 runAsGroup           : '0',
																								 command              : 'cat'),
															],
								envVars     : [containerEnvVar(key  : 'HOME',
																							 value: '/home/jenkins/agent'),
															],
								volumes     : [persistentVolumeClaim(claimName        : 'vm-modules-pvc',
																										 mountPath        : '/lib/modules'),
															 nfsVolume(serverAddress                : "${env.APT_REPO_NFS}",
																				 serverPath                   : '/share-rw',
																				 mountPath                    : '/repo'),
															 configMapVolume(configMapName          : 'dcbe-pbuilder-hooks',
																							 mountPath              : '/usr/lib/pbuilder/hooks'),
															],
							 )
		{
			node ("${podlabel}") {
				stage('checkout') {
					def gitscm = checkout scm
					env.GIT_BRANCH = gitscm.GIT_BRANCH
				}
				container('pbuilder') {
					stage('prepare buildenv') {
						sh "modprobe binfmt_misc"
						sh "update-binfmts --enable qemu-arm"
						sh "update-binfmts --enable qemu-aarch64"
					}
					stage('build package') {
						env.EATMYDATA = 'yes'
						sh """#!/bin/bash
set -xe
# hooks are mounted as configmap, so they are not executable
mkdir -p /usr/lib/pbuilder/hooks-exec
cp -H /usr/lib/pbuilder/hooks/* /usr/lib/pbuilder/hooks-exec
chmod 755 /usr/lib/pbuilder/hooks-exec/*

# by default checkout belongs to jenkins user, gbp requires it to be the id of the user (root)
chown -R `id -u` `pwd`
# jenkins doesnt checkout origin branches, however gpb needs the upstream branches
for upstream in `git branch -r | grep upstream | sed 's/origin\\///' | xargs echo -n`; do
  git checkout \$upstream
done
git checkout ${env.GIT_BRANCH} -B build-${arch}-${release}
# if the project has a watch file, use it to retrieve the original sources
if [ -f debian/watch ]; then
  git branch upstream
  git config --global user.email "dcbe@dcbe"
  git config --global user.name "DCBE Team"
  uscan --download-current-version
  gbp import-orig --no-interactive --debian-branch=build-${arch}-${release} --upstream-branch=upstream ../*orig.tar.*
fi
gbp buildpackage --git-pbuilder --git-dist=${release} --git-arch=${arch} \
                 --git-debian-branch=build-${arch}-${release} --git-upstream-tree=BRANCH \
                 --git-pbuilder-options=\"--hookdir /usr/lib/pbuilder/hooks-exec --debbuildopts \'-b\'\"
rm -f ../*_source.changes
cp ../*.buildinfo ../*.deb ../*.dsc ../*.tar.* ../*.changes /repo/incoming/
           """
					}
				}
      }
		}
	}
}
