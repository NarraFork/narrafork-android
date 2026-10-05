# syntax=docker/dockerfile:1
# NarraFork Android rootfs — Debian 12 (bookworm), linux/arm64.
#
# 构建（本机 x86_64 交叉构建，借助 qemu binfmt）：
#   podman build --platform linux/arm64 -t narrafork-android-rootfs .
#
# 注意：基础镜像 debian:12 默认无 ca-certificates，对 https 镜像首次
# apt-get update 会握手失败。因此先用 http:// 国内镜像引导，装好证书后再切回 https://。

FROM --platform=linux/arm64 debian:12

ENV DEBIAN_FRONTEND=noninteractive \
    NARRAFORK_ANDROID=1 \
    NARRAFORK_PROOT=1

# 阶段 1：http 引导装包（此时尚无 CA 证书，必须用 http 镜像）。
# debian:12 使用 deb822 格式的 /etc/apt/sources.list.d/debian.sources。
RUN set -eux; \
    sed -i \
      -e 's|http://deb.debian.org/debian-security|http://mirrors.tuna.tsinghua.edu.cn/debian-security|g' \
      -e 's|http://deb.debian.org/debian|http://mirrors.tuna.tsinghua.edu.cn/debian|g' \
      /etc/apt/sources.list.d/debian.sources; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
      ca-certificates \
      git \
      bash \
      dtach \
      ripgrep \
      sqlite3 \
      procps \
      curl \
      less \
      tar \
      xz-utils \
      grep \
      sed \
      coreutils \
      ; \
    rm -rf /var/lib/apt/lists/*

# 阶段 2：CA 证书就位后切回 https，减少明文传输面。
RUN set -eux; \
    sed -i 's|http://mirrors.tuna.tsinghua.edu.cn|https://mirrors.tuna.tsinghua.edu.cn|g' \
      /etc/apt/sources.list.d/debian.sources; \
    apt-get update -qq; \
    rm -rf /var/lib/apt/lists/*

# 常规目录与工具脚本。
RUN mkdir -p /opt/narrafork /usr/local/bin /root/.narrafork /root/projects

# overlays/ 下的文件按原路径铺进 rootfs（usr/local/bin/narrafork-*、etc/profile.d/、opt/narrafork/）。
COPY overlays/ /

RUN set -eux; \
    chmod +x /usr/local/bin/narrafork-start /usr/local/bin/narrafork-mirror; \
    if [ -d /opt/narrafork ]; then chmod +x /opt/narrafork/* 2>/dev/null || true; fi; \
    update-ca-certificates || true

WORKDIR /root
CMD ["/bin/bash"]
