#!/bin/bash

set -e

if [ -z "$1" ]; then
	echo "USAGE: $0 <kubernetes-namespace>"
	exit 1
fi

KCTL="kubectl -n $1 apply -f"

echo "CREATE NAMESPACE IF NEEDED"
kubectl get namespace $1 || kubectl create namespace $1

echo "DEPLOY APT REPO SERVER"

mkdir -p gpg
chmod 700 gpg

gpg --homedir=gpg --generate-key
GPGKEYID=`gpg --homedir=gpg -K | grep '^\ \ ' | sed 's/ //g'`

gpg --homedir=gpg --export-secret-keys --armor > .priv.key
gpg --homedir=gpg --export --armor > .pub.key
kubectl -n $1 create secret generic dcbe-repo-keys --from-file=repo-priv=.priv.key --from-file=repo-pub=.pub.key

cat > distributions << EOF
Origin: dcbe
Label: DCBE Repository
Description: DCBE debian package repository.
Codename: dcbe
Suite: bookworm
Architectures: i386 amd64 armhf arm64 source
Components: main
SignWith: $GPGKEYID
DebIndices: Packages Release . .gz .xz
UDebIndices: Packages . .gz .xz
DscIndices: Sources Release .gz .xz
Contents: . .gz .xz
EOF

cat > incoming << EOF
Name: dcbe
IncomingDir: /repo/incoming
TempDir: /tmp/reprepro
Default: dcbe
EOF

kubectl -n $1 create configmap dcbe-repo-distributions-conf --from-file distributions --from-file incoming

$KCTL dcbe-repo-nginx-conf.yaml
$KCTL dcbe-repo-nfs-exports-conf.yaml
$KCTL dcbe-repo-pvc.yamlHTTPSERVER=`kubectl -n $1 get service dcbe-repo-http-service \
                   --template={{.spec.clusterIP}}`

$KCTL dcbe-repo-server.yaml
$KCTL dcbe-repo-nfs-service.yaml
$KCTL dcbe-repo-http-service.yaml

NFSSERVER=`kubectl -n $1 get service dcbe-repo-nfs-service \
                   --template={{.spec.clusterIP}}`

HTTPSERVER=`kubectl -n $1 get service dcbe-repo-http-service \
                   --template={{.spec.clusterIP}}`

cat > dcbe-repo-nfs-share-pv.yaml << EOF
apiVersion: v1
kind: PersistentVolume
metadata:
  name: dcbe-repo-nfs-share
spec:
  capacity:
    storage: 500Gi
  accessModes:
    - ReadWriteMany
  nfs:
    server: $NFSSERVER
    path: "/share-rw"
  mountOptions:
    - nolock,vers=3
EOF

$KCTL dcbe-repo-nfs-share-pv.yaml

echo "DEPLOY JENKINS OPERATOR"
JOPURL="https://raw.githubusercontent.com/jenkinsci/kubernetes-operator/master"
$KCTL $JOPURL/config/crd/bases/jenkins.io_jenkins.yaml
$KCTL $JOPURL/deploy/all-in-one-v1alpha2.yaml

echo "DEPLOY JENKINS MASTER"
cp dcbe-jenkins-conf.yaml dcbe-jenkins-conf-gen.yaml
sed -i "s/INSERT_NFS_IP_HERE/$NFSSERVER/g" dcbe-jenkins-conf-gen.yaml
sed -i "s/INSERT_HTTP_IP_HERE/$HTTPSERVER/g" dcbe-jenkins-conf-gen.yaml
$KCTL dcbe-jenkins-conf-gen.yaml
$KCTL dcbe-jenkins.yaml

cat > H10-update.sh << EOF
#!/bin/sh

echo "Package: *
Pin: origin "$HTTPSERVER"
Pin-Priority: 910" > /etc/apt/preferences

apt update
apt install -y curl
mkdir -p /usr/share/keyrings
curl -o /usr/share/keyrings/dcbe.gpg http://$HTTPSERVER/dcbe.gpg

echo "deb [signed-by=/usr/share/keyrings/dcbe.gpg] http://$HTTPSERVER dcbe main" > /etc/apt/sources.list.d/dcbe.list
apt update
apt dist-upgrade -y
EOF

kubectl -n $1 create configmap dcbe-pbuilder-hooks \
  --from-file=H10-update.sh=H10-update.sh

echo "CREATING VOLUMES"
$KCTL vm-modules-pvc.yaml
$KCTL vm-modules-pv.yaml

OPPASS=`kubectl -n $1 get secret jenkins-operator-credentials-dcbe \
                --template={{.data.password}} | base64 --decode`

echo "JENKINS LOGIN: user: jenkins-operator pass: $OPPASS"
