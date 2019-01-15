#!/bin/bash
my_dir="$(dirname "$0")"
display_usage() {
	echo "This script runs the setting files generated by ConfigGenerator.sh."
	echo -e "\nUsage:\nScript [topology file name (no dir needed)] \n"
	}
if [ "$#" -le "0" ]; then
	display_usage
	exit 1
fi
while read -r line || [[ -n "$line" ]]; do
    IFS=':, ' read -r -a array <<< "$line"
    if [ "${#array[@]}" -ge "1" ]; then
		mkdir -p "/tmp/scorex/data${array[0]}/log"
		nohup sbt "; project examples; runMain examples.prism1.PrismV1App src/main/resources/testbench/settings${array[0]}.conf" > /tmp/scorex/data${array[0]}/log/stdout.data 2>&1 &
		echo "start ${array[0]}"
		sleep 3
    fi
done < "$my_dir/$1"

#sbt "; project examples; runMain examples.hybrid.HybridApp src/main/resources/settings.conf"

#how to terminate:
#kill -9 `ps -h | grep java | grep -v sbt-launch | grep -v grep | awk '{print $1}'`
#or
#ps -h | grep java | grep -v sbt-launch | grep -v idea | grep -v grep | awk '{print $1}'