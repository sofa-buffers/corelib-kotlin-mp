FROM ubuntu:26.04

ENV DEBIAN_FRONTEND=noninteractive

# valgrind drives bench/run_callgrind.sh — the one benchmark tool of the three
# that reports a machine-independent number (Ir/op). Without it the shipped
# environment cannot run a tool the repository ships (CORELIB_PLAN §10).
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    gnupg \
    git \
    unzip \
    zip \
    jq \
    valgrind \
    build-essential \
    pkg-config \
    zlib1g-dev \
    libtinfo6 \
    libncurses6 \
    && rm -rf /var/lib/apt/lists/*

# JDK — the host toolchain for every Kotlin Multiplatform target.
#
# Kotlin/JVM and the Gradle daemon itself run on this JDK; Kotlin/Native and
# Kotlin/JS builds are also driven by Gradle, so a JDK is required even for
# targets that never emit a class file. Temurin 21 (LTS) is the version the
# Kotlin toolchain resolves against in CI.
RUN mkdir -p -m 755 /etc/apt/keyrings \
    && curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
        | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg \
    && chmod go+r /etc/apt/keyrings/adoptium.gpg \
    && echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo "$VERSION_CODENAME") main" \
        > /etc/apt/sources.list.d/adoptium.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends temurin-21-jdk \
    && rm -rf /var/lib/apt/lists/* \
    && ln -s "/usr/lib/jvm/temurin-21-jdk-$(dpkg --print-architecture)" /opt/java-current \
    && /opt/java-current/bin/javac -version

# Arch-independent: the Temurin package installs to a -amd64/-arm64 suffixed
# directory, so the symlink above is what JAVA_HOME can safely point at.
ENV JAVA_HOME=/opt/java-current
ENV PATH="$JAVA_HOME/bin:$PATH"

# Gradle.
#
# Builds normally go through the project's Gradle wrapper; this pinned
# distribution exists so `gradle wrapper` can be run in a fresh checkout and so
# the container has a usable build tool before any wrapper is committed.
ARG GRADLE_VERSION=8.14.5
RUN curl -fsSL -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
    && unzip -q /tmp/gradle.zip -d /opt \
    && rm /tmp/gradle.zip \
    && ln -s "/opt/gradle-${GRADLE_VERSION}/bin/gradle" /usr/local/bin/gradle

# Kotlin/Native writes its downloaded LLVM toolchain and platform libraries
# here; pinning it keeps that cache out of the mounted workspace.
ENV KONAN_DATA_DIR=/opt/konan

# GitHub CLI (gh)
RUN mkdir -p -m 755 /etc/apt/keyrings \
    && curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
        | tee /etc/apt/keyrings/githubcli-archive-keyring.gpg > /dev/null \
    && chmod go+r /etc/apt/keyrings/githubcli-archive-keyring.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
        > /etc/apt/sources.list.d/github-cli.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends gh \
    && rm -rf /var/lib/apt/lists/*

# Node.js (LTS) — Claude Code, and the runtime for Kotlin/JS tests
RUN curl -fsSL https://deb.nodesource.com/setup_lts.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

# Claude Code
RUN npm install -g @anthropic-ai/claude-code

# Fail the build here rather than at the first Gradle invocation if any tool
# above did not land on PATH.
RUN java -version && javac -version && gradle --version && gh --version \
    && node --version && npm --version && valgrind --version

WORKDIR /workspace

CMD ["/bin/bash"]
