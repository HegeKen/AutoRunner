# 版本信息

> 官方原文: https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2141
> 更新时间: 1761200402627
> 本文件由页面内嵌 JSON（__NEXT_DATA__.props.pageProps.defaultContent）转换而来，仅用于离线查阅。


# 一、版本支持范围

**1. 版本支持：**

- OS2： 焦点通知（ 状态栏、通知中心、锁屏、息屏、小折叠外屏 ）
- OS3： 小米超级岛通知（ 包含岛摘要态、岛展开态、焦点通知除状态栏外各个位置 ）
**2. 机型支持：**

- 暂不限制机型，以系统版本为主

# 二、版本形态差异

差异：

①OS2存在常驻状态栏，OS3升级为岛状态

②OS2与OS3通知模版有升级与新增，详情请见[《小米超级岛模板库》](https://mag-admin.cnbj1.mi-fds.com/mag-admin/island/小米超级岛通知模板库.pdf)

OS2示意：

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761100280315-os2.png)
小米超级岛与焦点通知的数据接口相同，Xiaomi HyperOS 3支持更丰富的展开态模板，因此在接入Xiaomi HyperOS 3小米超级岛能力时，可通过如下方式兼容Xiaomi HyperOS 2焦点通知：

- 选择同时适用Xiaomi HyperOS 3和Xiaomi HyperOS 2的展开态模板，实现OS3和OS2相同的展示样式；
- 选择仅适用于Xiaomi HyperOS 3和可适用于Xiaomi HyperOS 2的2套展开态模板，实现OS3和OS2不同展示样式；
- 根据Xiaomi HyperOS版本下发不同版本数据。
* Xiaomi HyperOS 系统版本判断方式参考[《开发指南》](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131)
