FROM eclipse-temurin:21-jdk

RUN apt-get update && apt-get install -y --no-install-recommends \
    maven \
    python3 \
    python3-pip \
    locales \
    && pip3 install --break-system-packages pyyaml \
    && rm -rf /var/lib/apt/lists/*

RUN locale-gen zh_CN.UTF-8

ENV LANG=zh_CN.UTF-8
ENV LC_ALL=zh_CN.UTF-8
ENV PYTHONIOENCODING=utf-8
ENV MAVEN_OPTS="-Dfile.encoding=UTF-8"

WORKDIR /project

ENTRYPOINT ["mvn", "test"]
