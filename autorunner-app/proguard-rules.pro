# AutoRunner R8 / ProGuard 规则
#
# 默认规则：proguard-android-optimize.txt
# .arscript 模型的 kotlinx.serialization 由编译期插件生成
# Serializer（直接字段访问），无需 -keep 序列化模型；反射入口只有下方的
# LSPosed 模块类。
# 本文件仅按 R8 的实际告警追加最小定向规则，禁止整包 -keep。
#
# LSPosed 模块：入口类由 META-INF/xposed/java_init.list 按类名反射加载，
# 框架还会回调 onPackageReady，因此不能混淆或裁剪。libxposed API 只在编译期
# 可见（compileOnly），运行期由宿主进程注入，R8 不必为缺失的实现告警。
-keep class cn.helilab.autorunner.xposed.AutoRunnerXposed { *; }
-dontwarn io.github.libxposed.**
