def call(String img, String buildargs)
{
	def label = "containerimgbuild-${UUID.randomUUID().toString()}"

	podTemplate(label  : label,
	            volumes: [persistentVolumeClaim(claimName: 'vm-modules-pvc',
	                                            mountPath: '/lib/modules'),
	                     ],
	            containers: [containerTemplate(name                 : 'debian',
	                                           image                : "debian:bullseye-slim",
	                                           resourceRequestCpu   : '1000m',
	                                           resourceRequestMemory: '1Gi',
	                                           resourceLimitCpu     : '1000m',
	                                           resourceLimitMemory  : '1Gi',
	                                           ttyEnabled           : true,
	                                           privileged           : true,
	                                           command              : 'cat'),
	                        ],
	            yaml   : """
kind: Pod
spec:
  containers:
  - name: kaniko
    image: gcr.io/kaniko-project/executor:v1.7.0-debug
    resources:
      limits:
        memory: "32Gi"
      requests:
        memory: "32Gi"
    securityContext:
        privileged: true
    command:
    - sleep
    args:
    - 99d
    volumeMounts:
      - name: jenkins-docker-cfg
        mountPath: /kaniko/.docker
  volumes:
  - name: jenkins-docker-cfg
    projected:
      sources:
      - secret:
          name: container-reg-cred
          items:
            - key: .dockerconfigjson
              path: config.json
"""
	)
	{
		node (label) {
			container('debian') {
				stage('enable binfmt') {
					sh "apt update"
					sh "apt install -y binfmt-support kmod qemu-user-static"
					sh "modprobe binfmt_misc"
					sh "update-binfmts --enable qemu-arm"
					sh "update-binfmts --enable qemu-aarch64"
				}
			}
			stage('Clone repository') {
				checkout scm
			}
			container('kaniko') {
				stage('Build image') {
					sh "/kaniko/executor -f `pwd`/Dockerfile -c `pwd` \
					    --insecure --skip-tls-verify \
					    --snapshotMode=redo \
					    --destination=${env.DOCKER_REGISTRY}/${img} \
					    --build-arg DOCKER_REGISTRY=${env.DOCKER_REGISTRY} ${buildargs}"
				}
			}
		}
	}
}
