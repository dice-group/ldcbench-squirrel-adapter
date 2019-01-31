default: build

build:
	docker-compose -f docker-compose.yml down
	mvn clean install -U -DskipTests -Dmaven.javadoc.skip=true

dockerize:
	docker build -f Dockerfile -t adapter.squirrel .
