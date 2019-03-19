IMAGE = git.project-hobbit.eu:4567/ldcbench/ldcbench-squirrel-adapter

build:
	mvn clean package -DskipTests

dockerize: build
	docker build -t $(IMAGE) .

test-benchmark:
	mvn -Dtest=BenchmarkTest#checkHealth test

package:
	mvn -DskipTests -DincludeDeps=true package

test-dockerized-benchmark:
	mvn -Dtest=BenchmarkTest#checkHealthDockerized test


push-images:
	docker push $(IMAGE)

add-hobbit-remote:
	git remote |grep hobbit ||git remote --verbose add hobbit https://git.project-hobbit.eu/ldcbench/ldcbench-squirrel-adapter

push-hobbit: add-hobbit-remote
	git push --verbose hobbit master
