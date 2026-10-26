# 媒体通知岛上拖拽分享适配说明

> 官方原文: https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2162
> 更新时间: 1763623321021
> 本文件由页面内嵌 JSON（__NEXT_DATA__.props.pageProps.defaultContent）转换而来，仅用于离线查阅。


# 一、媒体通知上岛简介

## 1. 基础形态

如应用已接入媒体通知则展示效果如下图，若还未接入媒体通知，请参考：[《Xiaomi HyperOS 媒体通知适配说明》](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2161)

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1763620547299-image%20(30).png)

## 2. 拖拽分享

**场景介绍**

用户在后台听歌时，当发现该歌曲适合分享给亲友时，无需回到应用内分享，在对话框直接拖拽小米超级岛即可调用分享能力，完成分享行为

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1763620574012-image%20(31).png)

# 二、适配说明

## 1. 参数说明

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1763620606673-image%20(32).png)

## 2. 代码示例

```
//发送通知时携带分享信息String islandParams =        "{" +                "    \"param_v2\": {" +                "        \"param_island\": {" +                "            \"shareData\": {" +                "               \"title\": \"音乐标题\",\n" +   // 拖拽及分享后卡片显示标题                "               \"pic\": \"xxx\",\n" +         // 媒体通知使用为封面图，无需设置                "               \"content\": \"分享内容\",\n" + // 拖拽及分享后卡片显示内容                "               \"shareContent\": \"https://i.y.qq.com/n2/m/musiclite/playsong/index.html?app_type=qmlite&songmid=003kva882toU7E\",\n" +                 // 分享链接                "           }" +                "        }" +                "    }" +                "}";notification.extras.putString("miui.focus.param.media", islandParams);notificationManager.notify(1, notification);
```
