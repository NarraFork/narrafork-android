# NarraFork Android 容器 profile 钩子
export NARRAFORK_ANDROID="${NARRAFORK_ANDROID:-1}"
export NARRAFORK_PROOT="${NARRAFORK_PROOT:-1}"
# 让 narrafork-* 工具在交互 shell 下立即可用
case ":$PATH:" in
	*":/usr/local/bin:"*) ;;
	*) export PATH="/usr/local/bin:$PATH" ;;
esac
