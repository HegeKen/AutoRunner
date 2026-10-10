# AutoRunner R8 / ProGuard 规则
#
# 默认规则：proguard-android-optimize.txt
# 项目无反射调用；.arscript 模型的 kotlinx.serialization 由编译期插件生成
# Serializer（直接字段访问），无需 -keep 序列化模型。
# 本文件仅按 R8 的实际告警追加最小定向规则，禁止整包 -keep。
