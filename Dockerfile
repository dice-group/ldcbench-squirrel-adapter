FROM maven

RUN mkdir -p /usr/src/app
WORKDIR /usr/src/app

ADD /target/squirrel-ldcbench-adapter-shaded.jar /usr/src/app

CMD ["java", "-cp", "squirrel-ldcbench-adapter-shaded.jar", "org.hobbit.core.run.ComponentStarter", "org.dice_research.squirrel.adapter.system.SystemAdapter"]
