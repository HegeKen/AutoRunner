# 接入流程

> 官方原文: https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2132
> 更新时间: 1764928260312
> 本文件由页面内嵌 JSON（__NEXT_DATA__.props.pageProps.defaultContent）转换而来，仅用于离线查阅。


# 一、开发准备

## 1、注册成为开发者

**1.1 注册小米账号并绑定邮箱**
前往[小米账号中心](https://account.xiaomi.com/)注册小米账号，详细操作见[如何注册小米账号](https://account.xiaomi.com/helpcenter/faq/zh_CN/01.guide/01.register/faq-1)；若已有小米账号，使用已有小米账号完成认证（需确认已有小米账号未进行开发者认证）

**1.2 开发者认证**
参照[开发者账号注册标准](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1731)和[企业开发者账号注册认证操作指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1145)进行注册认证，如果遇问题，可以查询[开发者账号常见问题](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1144)，如问题仍未解决，联系客服进行咨询 经过企业管理员认证、信息采集等环节，提交认证材料，进行人工审核

**1.3 认证审核**

- 开平就开发者的申请进行审核，在1-3个工作日内完成审核并反馈结果，反馈内容为：
- 审核通过可进行下一步操作
- 审核未通过，开发者需根据驳回理由进行修改，修改后，重新提交
- 如有问题，可以通过查看Q＆A以及 联系客服 解决

## 2、创建应用

**2.1 创建应用**
参照[应用创建操作指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1072)，创建应用。 注意：由于国内和海外的开平相互独立，海外应用暂不支持接入国内开平的能力

**2.2 完善应用资料**
参照[应用创建操作指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1072)，完善应用资料并上传包体。 注意：由于国内和海外的开平相互独立，海外应用暂不支持接入国内开平的能力

**2.3 应用审核**

- 开平就开发者的申请进行审核，在1-3个工作日内完成审核并反馈结果，如有任何问题，可 联系客服 ，审核结束后，反馈内容为：

# 二、开通MIpush推送服务

若开发者需要通过push云侧创建、更新和结束小米超级岛，在开通小米超级岛服务前，开发者需要首先为项目开通“推送服务”权益，详情请参见[开通推送服务](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1541)。

- 开通推送服务后，开发者可通过 MIpush服务实现 小米超级岛。
说明

若开发者仅需在本地通过客户端创建、更新和结束小米超级岛，请直接开通小米超级岛服务。

# 三、开通小米超级岛服务

## 1、启用服务

- 进入手机/平板应用选择您需要开通小米超级岛的在架应用
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761039634464-img_v3_02r9_bc2fecf5-7d0f-42f4-8e60-d5c375f3ec3g.png)
- 在应用详情页进入开发菜单下的开放服务
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761039728670-img_v3_02r9_27e9b287-856b-4cb4-ac39-dbea93e4e92g.png)
- 选择小米超级岛服务
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761111750654-image.png)
- 点击服务启用
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761040683590-img_v3_02r9_a4754456-928a-454d-ae34-5d29df9c332g.jpg)
- 填写联系信息
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761040744065-img_v3_02r9_51ec9f50-159b-4680-8c93-a05422bf872g.jpg)
- 配置指纹证书
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761040135373-image.png)
- 服务启用成功
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761040778013-img_v3_02r9_a217d533-6136-4cc5-94eb-4b83a574152g.jpg)

## 2、场景和方案提报

**2.1 上岛场景预审**
开发者通过文案简要描述业务上岛场景和核心信息，审核通过后可提交完整方案。如果上岛场景包含运营活动、促销、借款等不符合产品定位的内容，可能会被驳回。

- 点击小米超级岛菜单进入该页面
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761040813648-img_v3_02r9_101797ef-b3a2-45d0-a635-e4392775161g.jpg)
- 首次进入需要在下方场景列表中选择场景并申请，如果没有找到对应场景，请选择（其他）场景。
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761040357968-img_v3_02r9_1ea1e712-069a-4ce5-988d-b2e470b04e4g.png)
- 填写申请信息，并完成提交。
MIpush服务实现：应用客户端无需保持活跃，可在未开启客户端时，通过MIpush发送小米超级岛信息。
客户端实现：应用客户端需保持活跃（后台运行），通过客户端发送小米超级岛信息。

选择MIpush服务实现，请先确保应用已[开通推送服务](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1541)。

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761040872277-img_v3_02r9_a0e67a23-521d-4f2e-9da6-17f38a516c1g.jpg)
**2.2 正式方案审核**
预审审核通过的场景，开发者需补充提交完整的应用上岛方案，包括从通知开始到结束过程中的所有变化节点，以及节点对应的信息和呈现效果图。填写样式可参考方案提报模板示例

- 从的我的场景进入，选择预审通过的场景，点击【前往填写】
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761040913789-img_v3_02r9_9f7b409b-bd13-4ebd-a11f-be7fd54209eg.jpg)
- 填写申请信息，并完成提交。正式方案审核通过后，可进行开发和联调测试。
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761040961064-img_v3_02r9_6a3e526b-c7dd-4f75-916d-59c460f1dbag.jpg)

## 3、接入联调测试

若开发者需要在设备上调试、验证小米超级岛，可通过“设备白名单管理”入口，添加设备进行调测。添加到白名单中的设备，不做本地构建超级岛权限的校验**。**
设备白名单管理能力可用于应用小米超级岛场景上线前的验证，体验应用即将上线的小米超级岛场景。
**在白名单设备上对小米超级岛充分测试后，开发者可申请上线验证，通过后可开通超级岛正式权限。**

**3.1 约束和限制**

- 设备白名单有效期为30天，过期后自动失效，需重新添加。
- 白名单设备管理需根据OAID添加白名单设备，每个应用可添加的设备上限为10。 3.1.1 添加白名单调试权限设备
1. 点击【设备白名单管理】进行添加
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761041082072-tKVGhDcJZu.png)
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761041124587-image.png)
**3.3 添加 App** **Id 和构建版本 Debug 标识**
根据应用使用的签名，配置对应的Debug 标识，鉴权时会验证应用 apk 签名。开发调试阶段，一般不会运行 Release 签名的 apk，通常都是运行 Debug 签名 apk，为了保证在开发阶段仍然可以鉴权成功，开发者可以在平台同时添加正式证书指纹（Release 签名）和测试证书指纹（Debug 签名），用于不同场景下鉴权。

- App Id
`com.xiaomi.xms.APP_ID` 当开通服务时，会生成 App Id 唯一标识，用于鉴权 **必须配置 App** **Id，调用服务时会通过 App Id 进行鉴权**

- 构建版本 Debug 标识
`com.xiaomi.xms.BUILD_TYPE_DEBUG` 当前是否为 Debug 构建版本。**若不配置，默认为 false，应用使用测试签名调试时，应为true，使用正式签名时，应为false。**

- 在应用的 AndroidManifest.xml 中添加 meta-data 配置

```
<?xml version="1.0" encoding="utf-8"?><manifest xmlns:android="http://schemas.android.com/apk/res/android">    <application>               <meta-data            android:name="com.xiaomi.xms.APP_ID"            android:value="your app id here" />                    <meta-data            android:name="com.xiaomi.xms.BUILD_TYPE_DEBUG"            android:value="true or false" />       </application></manifest>
```

**3.4 OS2焦点通知接入联调测试**
应用如需在OS2发送焦点通知，请参考以下流程进行OS2开发工具包与临时权限申请
开发者发起申请邮件至 mipush-permission@xiaomi.com

1. 主题：【申请小米OS2焦点通知开发测试包】 -yymmdd-「APP名称」
2. 正文：
【应用信息】：应用名称、包名、appid
【联系方式】
【所申请的通知权限】：焦点通知
【附件（必传）】：场景审核通过截图，测试机版本截图

发送邮件申请后，OS2开发工具包将以附件形式发送到开发者邮箱，请及时查看内容和使用方法。

## 4、获得小米超级岛正式权限

开发者充分自测后，需要提交已通过测试的应用正式环境apk安装包，及上岛场景的验证方法进行上线验证。如果场景复杂不易构建，需提供已添加业务白名单的测试账号。验证通过后应用会获取小米超级岛正式权限，即可发版上线。
注意：如果应用同时接入了OS2焦点通知和OS3小米超级岛，需要将代码合并到APK后统一进行验证。

- 从管理中心线上验证进入，申请验证
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761041180171-nmRyWk97MI.png)
- 填写申请信息，并完成提交。验证通过后应用会获取小米超级岛正式权限，即可发版上线。
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1764928196943-55db4176-1d40-4ebf-ae45-2ad94f43be95.png)
**4.1 灰度上线**
初次获得小米超级岛正式权限的应用，小米超级岛会进行灰度逐步放量，待应用上线后约7~15天时间，全量到100%。

**4.2 扩充应用场景**
应用新增场景接入小米超级岛，需要重新提交新场景的方案经过预审、正式审核、上线验证流程。未经审核的场景上线，会导致应用的小米超级岛服务停用，如果造成严重后果，可能导致应用下架。
