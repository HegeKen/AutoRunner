# 展开态设计规范

> 官方原文: https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2142
> 更新时间: 1769654941630
> 本文件由页面内嵌 JSON（__NEXT_DATA__.props.pageProps.defaultContent）转换而来，仅用于离线查阅。

小米超级岛摘要态和展开态模版需按照已有模版进行选择，模版由多个组件构成，暂不支持自行拼接，请注意各模版所支持的OS版本，新版本模版无法向下兼容旧版本模版（*OS3模版OS2无法使用，如需在OS2上展示需选择OS2模版进行单独适配*）。为确保体验效果，所有提交方案均需通过设计规范审核，未达标应用将无法获得正式上线权限。

# 一、OS2/OS3相同模版（适配OS3自动兼容OS2）

## 1、 天气/导航等参考模版

**模版1——文本组件1 + 识别图形组件3**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761013398427-1.png)
**OS3示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761013398540-2.png)
**OS2示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761013398525-3.png)
**Flip外屏示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761013398583-4.png)
**文本组件1 + 识别图形组件3**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761013398614-5.png)
**文本组件1：**

- 必传内容：主要文本1、次要文本1
- 主要文本：关键信息
- 次要文本：前置描述
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761013398598-6.png)
**识别图形组件3：**

- 必传内容：图片
- 建议像素不小于224*224px
- 圆角说明：如果图片需要圆角，建议圆角数值取图片尺寸1/4，例如图片为48*48dp，圆角取12dp

## 2、 支付等参考模版

**模版2——文本组件2 + 识别图形组件1**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761014012467-1.png)
**OS3示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761014012616-2.png)
**OS2示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761014012664-3.png)
**Flip外屏示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761014012709-4.png)
**文本组件2 + 识别图形组件1**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761014012702-5.png)
**文本组件2：**

- 必传内容：主要文本1、次要文本1
- 主要文本：关键信息
- 次要文本：前置描述
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761014012708-6.png)
**识别图形组件1：**

- 系统默认取桌面图标
- 支持自定义，正方形图片，建议像素不小于96*96px
- 圆角说明：如果图片需要圆角，建议圆角数值取图片尺寸1/4，例如图片为24*24dp，圆角取6dp

## 3、 callkit/视频通话等参考模版

**模版3——IM图文组件 + 识别图形组件2**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761014194434-1.png)
**OS3示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761014194610-2.png)
**OS2示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761014194541-3.png)
**Flip外屏示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761014194535-4.png)
**IM图文组件 + 识别图形组件2**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761014194623-5.png)
**IM图文组件：**

- 必传内容：头像图、主要文本、次要文本
- 头像图：正方形图片，建议像素不小于224*224px
- 主要文本：关键信息
- 次要文本：补充描述
- 应用图标：
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761014194638-6.png)
**识别图形组件2：**

- 必传内容：功能图标
- 正方形图片，建议像素不小于176*176px
- 圆角说明：如果图片需要圆角，建议圆角数值取图片尺寸1/4，例如图片为48*48dp，圆角取12dp

## 4、 打车/外卖等模版参考

**模版4——文本组件2 + 识别图形组件1 + 进度组件1**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761014592754-1.png)
**OS3示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761133055545-%E6%9B%B4%E6%96%B0%E5%90%8E%E5%9B%BE%E7%89%871(1).png)
**OS2示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761123683419-%E6%9B%B4%E6%96%B0%E5%90%8E%E5%9B%BE%E7%89%871.png)
**Flip外屏示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761014592713-4.png)
**文本组件2 + 识别图形组件1 + 进度组件1**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761014592949-5.png)
**文本组件2：**

- 必传内容：主要文本1、次要文本1
- 主要文本：关键信息
- 次要文本：前置描述
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761014592884-6.png)
**识别图形组件1**

- 系统默认取桌面图标
- 支持自定义，正方形图片，建议像素不小于96*96px
- 圆角说明：如果图片需要圆角，建议圆角数值取图片尺寸1/4，例如图片为24*24dp，圆角取6dp
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761014593004-7.png)
**进度组件1**

- 必传内容：当前进度、进度条颜色、前进图形、中间节点、目标点
- 当前进度：已完成进度条在全部进度条上的相对位置
- 进度条颜色：已完成进度的进度条颜色，支持自定义纯色、渐变色，推荐使用应用色
- 前进图形、中间节点、目标点的绘制规范见附录-进度条绘制规范

## 5、 排队/等位等参考模版

**模版5——文本组件1 + 识别图形组件1 + 进度组件2**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761014876774-1.png)
**OS3示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761014876858-2.png)
**OS2示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761014876727-3.png)
**Flip外屏示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761014876733-4.png)
**文本组件1 + 识别图形组件1 + 进度组件2**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761014876890-5.png)
**文本组件1：**

- 必传内容：主要文本1、次要文本1
- 主要文本：关键信息
- 次要文本：前置描述
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761014876879-6.png)
**识别图形组件1**

- 系统默认取桌面图标
- 支持自定义，正方形图片，建议像素不小于96*96px
- 圆角说明：如果图片需要圆角，建议圆角数值取图片尺寸1/4，例如图片为24*24dp，圆角取6dp
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761014876930-7.png)
**进度组件2**

- 必传内容：当前进度，进度条颜色
- 当前进度：已完成进度条在全部进度条上的相对位置
- 进度条颜色：已完成进度条的颜色，推荐和应用色相近的颜色

## 6、 停车计费等参考模版

**模版6——文本组件2 + 识别图形组件1 + 进度组件2**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761015139298-1.png)
**OS3示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761015139453-2.png)
**OS2示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761015139390-3.png)
**Flip外屏示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761015139278-4.png)
**文本组件2 + 识别图形组件1 + 进度组件2**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761015139379-5.png)
**文本组件2：**

- 必传内容：主要文本1、次要文本1
- 主要文本：关键信息
- 次要文本：前置描述
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761015139440-6.png)
**识别图形组件1**

- 系统默认取桌面图标
- 支持自定义，正方形图片，建议像素不小于96*96px
- 圆角说明：如果图片需要圆角，建议圆角数值取图片尺寸1/4，例如图片为24*24dp，圆角取6dp
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761015264695-7.png)
**进度组件2**

- 必传内容：当前进度，进度条颜色
- 当前进度：已完成进度条在全部进度条上的相对位置
- 进度条颜色：已完成进度条的颜色，推荐和应用色相近的颜色

## 7、 文件上传/下载等参考模版

**模版7——IM图文组件 +识别图形组件1 + 进度组件2**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761015368900-1.png)
**OS3示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761015368949-2.png)
**OS2示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761015368840-3.png)
**Flip外屏示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761015368901-4.png)
**IM图文组件 +识别图形组件1 + 进度组件2**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761015368962-5.png)
**IM图文组件**

- 必传内容：头像图、主要文本、次要文本
- 头像图：正方形图片，建议像素不小于224*224px
- 主要文本：关键信息
- 次要文本：补充描述
- 应用图标：
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761015368987-6.png)
**识别图形组件1**

- 系统默认取桌面图标
- 支持自定义，正方形图片，建议像素不小于96*96px
- 圆角说明：如果图片需要圆角，建议圆角数值取图片尺寸1/4，例如图片为24*24dp，圆角取6dp
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761015369232-7.png)
**进度组件2**

- 必传内容：当前进度，进度条颜色
- 当前进度：已完成进度条在全部进度条上的相对位置
- 进度条颜色：已完成进度条的颜色，推荐和应用色相近的颜色

## 8、 优惠券/红包等参考模版

**模版8——IM图文组件 + 识别图形组件1 + 按钮组件3**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761015557541-1.png)
**OS3示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761015557646-2.png)
**OS2示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761015557643-3.png)
**Flip外屏示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761015557615-4.png)
**IM图文组件 + 识别图形组件1 + 按钮组件3**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761015557665-5.png)
**IM图文组件**

- 必传内容：头像图、主要文本、次要文本
- 头像图：正方形图片，建议像素不小于224*224px
- 主要文本：关键信息
- 次要文本：补充描述
- 应用图标：
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761015557631-6.png)
**识别图形组件1**

- 系统默认取桌面图标
- 支持自定义，正方形图片，建议像素不小于96*96px
- 圆角说明：如果图片需要圆角，建议圆角数值取图片尺寸1/4，例如图片为24*24dp，圆角取6dp
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761015557697-7.png)
**按钮组件3**

- 必传内容：主要文本、按钮
- 主要文本：关键信息
- 图标文本按钮：
- 图文特殊标签：

## 9、 电影等参考模版

**模版9——文本组件2 + 识别图形组件1 + 按钮组件2**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761015857537-1.png)
**OS3示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761123864180-%E6%9B%B4%E6%96%B0%E5%90%8E%E5%9B%BE%E7%89%874.png)
**OS2示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1769654259681-20260129-103721.jpg)
**Flip外屏示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761123939054-%E6%9B%B4%E6%96%B0%E5%90%8E%E5%9B%BE%E7%89%876.png)
**文本组件2 + 识别图形组件1 + 按钮组件2**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761015857661-5.png)
**文本组件2：**

- 必传内容：主要文本1、次要文本1
- 主要文本：关键信息
- 次要文本：前置描述
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761015857688-6.png)
**识别图形组件1**

- 系统默认取桌面图标
- 支持自定义，正方形图片，建议像素不小于96*96px
- 圆角说明：如果图片需要圆角，建议圆角数值取图片尺寸1/4，例如图片为24*24dp，圆角取6dp
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761015857764-7.png)
**按钮组件2**

- 必传内容：前置文本1，主要小文本1，按钮文本
- 前置文本：补充描述
- 主要小文本：关键信息，支持计时器
- 图标文本按钮：

## 10、 快递/物流等参考模版

**模版10——文本组件2 + 识别图形组件1 + 按钮组件3**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761016045753-1.png)
**OS3示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761016045755-2.png)
**OS2示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761016045722-3.png)
**Flip外屏示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761016045759-4.png)
**文本组件2 + 识别图形组件1 + 按钮组件3**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761016045797-5.png)
**文本组件2：**

- 必传内容：主要文本1、次要文本1
- 主要文本：关键信息
- 次要文本：前置描述
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761016045807-6.png)
**识别图形组件1**

- 系统默认取桌面图标
- 支持自定义，正方形图片，建议像素不小于96*96px
- 圆角说明：如果图片需要圆角，建议圆角数值取图片尺寸1/4，例如图片为24*24dp，圆角取6dp
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761016045936-7.png)
**按钮组件3**

- 必传内容：主要文本、按钮
- 主要文本：关键信息
- 图标文本按钮：
- 图文特殊标签：

## 11、 运动健康等参考模版

**模版11——强调图文组件 + 识别图形组件1 + 按钮组件2**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761016221100-1.png)
**OS3示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761016221102-2.png)
**OS2示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761016221104-3.png)
**Flip外屏示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761016221327-4.png)
**强调图文组件 + 识别图形组件1 + 按钮组件2**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761016221169-5.png)
**强调图文组件**

- 必传内容：强调文本、功能图标
- 强调文本：需要强调的数据或内容，支持计时器
- 功能图标：正方形，建议像素不小于96*96px
- 辅助文本：补充描述
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761016221174-6.png)
**识别图形组件1**

- 系统默认取桌面图标
- 支持自定义，正方形图片，建议像素不小于96*96px
- 圆角说明：如果图片需要圆角，建议圆角数值取图片尺寸1/4，例如图片为24*24dp，圆角取6dp
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761016221268-7.png)
**按钮组件2**

- 必传内容：前置文本1，主要小文本1，按钮文本
- 前置文本：补充描述
- 主要小文本：关键信息，支持计时器
- 图标文本按钮：

# 二、OS2/OS3基本相同，OS3支持更多能力（适配OS3自动兼容OS2）

## 1、 电话/视频等参考模版

**模版12——IM图文组件 + 按钮组件1**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761025975374-1.png)
**OS3示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761025975441-2.png)
**OS2示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761025975465-3.png)
**Flip外屏示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761025975407-4.png)
**IM图文组件 + 按钮组件1**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761025975460-5.png)
**IM图文组件**

- 必传内容：头像图、主要文本、次要文本
- 头像图：正方形图片，建议像素不小于224*224px
- 圆角说明：如果图片需要圆角，建议圆角数值取图片尺寸1/4，例如图片为48*48dp，圆角取12dp
- 主要文本：关键信息
- 次要文本：补充描述
- 应用图标：
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761025975473-6.png)
**按钮组件1**

- 支持3种类型
- 普通按钮：
- 进度按钮：
- 文字按钮：
**OS2差异：**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761025975529-7.png)
OS2仅支持普通按钮，不支持进度、文字按钮

## 2、 录音等参考模版

**模版13——强调图文组件 + 按钮组件1**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761026214734-1.png)
**OS3示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761026214798-2.png)
**OS2示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761026214802-3.png)
**Flip外屏示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761026214794-4.png)
**强调图文组件 + 按钮组件1**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761026214827-5.png)
**强调图文组件**

- 必传内容：强调文本、功能图标
- 强调文本：需要强调的数据或内容，支持计时器
- 功能图标：正方形，建议像素不小于96*96px
- 圆角说明：如果图片需要圆角，建议圆角数值取图片尺寸1/4，例如图片为24*24dp，圆角取6dp
- 辅助文本：补充描述
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761026214878-6.png)
**按钮组件1**

- 支持3种类型
- 普通按钮：
- 进度按钮：
- 文字按钮：
**OS2差异点**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761026214943-7.png)
OS2仅支持普通按钮，不支持进度、文字按钮

# 三、OS3新增模版（仅适配OS3，OS2需选取OS2可适配模版）

## 1、 导航等参考模版

**模版14-1——新图文组件/新图文组件+倒计时带图组件OS3示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761026535404-2.png)
**Flip外屏示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761026535403-3.png)
**新图文组件**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761026535417-4.png)
**新图文组件**

- 图片，主要文本、次要文本1
- 图片：正方形图片，建议像素不小于224*224px
- 圆角说明：如果图片需要圆角，建议圆角数值取图片尺寸1/4，例如图片为48*48dp，圆角取12dp
- 主要文本：关键信息
- 次要文本：补充描述
**序号14-2——模版结构**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761026535439-5.png)
**OS3示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761026535553-6.png)
**Flip外屏示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761026535597-7.png)
**新图文组件+倒计时带图组件**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761026535665-8.png)
**新图文组件**

- 必传内容：图片、主要文本、次要文本1
- 图片：正方形图片，建议像素不小于224*224px
- 圆角说明：如果图片需要圆角，建议圆角数值取图片尺寸1/4，例如图片为48*48dp，圆角取12dp
- 主要文本：关键信息
- 次要文本：补充描述
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761026535722-9.png)
**倒计时带图组件**

- 必传内容：图片、文本、文本颜色

## 2、 验证码等参考模版

**模版16——新图文组件 + 按钮组件1**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761026997538-1.png)
**OS3示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761123997362-%E6%9B%B4%E6%96%B0%E5%90%8E%E5%9B%BE%E7%89%877.png)
**Flip外屏示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761124037346-%E6%9B%B4%E6%96%B0%E5%90%8E%E5%9B%BE%E7%89%878.png)
**新图文组件 + 按钮组件1**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761026997518-4.png)
**新图文组件**

- 必传内容：图片、主要文本、次要文本1
- 图片：正方形图片，建议像素不小于224*224px
- 圆角说明：如果图片需要圆角，建议圆角数值取图片尺寸1/4，例如图片为48*48dp，圆角取12dp
- 主要文本：关键信息
- 次要文本：补充描述
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761026997549-5.png)
**按钮组件1**

- 支持3种类型
- 普通按钮：
- 进度按钮：
- 文字按钮：

## 3、 订单待支付等参考模版

**模版17——新图文组件 + 识别图形组件1 + 按钮组件5**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027159860-1.png)
**OS3示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027159898-2.png)
**Flip外屏示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027159934-3.png)
**新图文组件 + 识别图形组件1 + 按钮组件5**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027159926-4.png)
**新图文组件**

- 必传内容：图片、主要文本、次要文本1
- 图片：正方形图片，建议像素不小于224*224px
- 圆角说明：如果图片需要圆角，建议圆角数值取图片尺寸1/4，例如图片为48*48dp，圆角取12dp
- 主要文本：关键信息
- 次要文本：补充描述
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027159835-5.png)
**识别图形组件1**

- 系统默认取桌面图标
- 支持自定义，正方形图片，建议像素不小于96*96px
- 圆角说明：如果图片需要圆角，建议圆角数值取图片尺寸1/4，例如图片为24*24dp，圆角取6dp
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027159902-6.png)
**按钮组件5**

- 必传内容：高亮文本、图标文本按钮
- 高亮文本：可自定义颜色
- 图标文本按钮：

## 4、 文件互传/分享等参考模版

**模版18——新图文组件 + 识别图形组件 + 按钮组件4**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027312624-1.png)
**OS3示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027312627-2.png)
**Flip外屏示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027312626-4.png)
**新图文组件 + 识别图形组件 + 按钮组件4**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027312687-5.png)
**新图文组件**

- 必传内容：图片、主要文本、次要文本1
- 图片：正方形图片，建议像素不小于224*224px
- 圆角说明：如果图片需要圆角，建议圆角数值取图片尺寸1/4，例如图片为48*48dp，圆角取12dp
- 主要文本：关键信息
- 次要文本：补充描述
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027312740-6.png)
**识别图形组件1**

- 系统默认取桌面图标
- 支持自定义，正方形图片，建议像素不小于96*96px
- 圆角说明：如果图片需要圆角，建议圆角数值取图片尺寸1/4，例如图片为24*24dp，圆角取6dp
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027312832-7.png)
**按钮组件4**

- 支持1-2个按钮

## 5、 抢票/预约等参考模版

**模版19——封面组件 + 识别图形组件1 + 按钮组件5**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1769588353365-image%20(33).png)
**OS3示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1769588400779-image%20(34).png)
**Flip外屏示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1769654333348-20260129-103838.jpg)
**封面组件 + 识别图形组件1 + 按钮组件5**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027469871-4.png)
**封面组件**

- 必传内容：封面图片、主要文本、次要文本1、次要文本2
- 封面图片：建议像素不小于224*288px
- 圆角说明：如果图片需要圆角，建议圆角数值取短边尺寸1/4，例如图片为48*64dp，圆角取12dp
- 主要文本：关键信息
- 次要文本：补充信息
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027469882-5.png)
**识别图形组件1**

- 系统默认取桌面图标
- 支持自定义，正方形图片，建议像素不小于96*96px
- 圆角说明：如果图片需要圆角，建议圆角数值取图片尺寸1/4，例如图片为24*24dp，圆角取6dp
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027469966-6.png)
**按钮组件5**

- 必传内容：高亮文本、图标文本按钮
- 高亮文本：可自定义颜色
- 图标文本按钮：

## 6、 套餐/流量余额等参考模版

**模版20——文本组件2 + 识别图形组件1 + 进度组件3**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027621746-1.png)
**OS3示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027621758-2.png)
**Flip外屏示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027621700-3.png)
**文本组件2 + 识别图形组件1 + 进度组件3**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027621816-4.png)
**文本组件2：**

- 必传内容：主要文本1、次要文本1
- 主要文本：关键信息
- 次要文本：前置描述
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027621807-5.png)
**识别图形组件1**

- 系统默认取桌面图标
- 支持自定义，正方形图片，建议像素不小于96*96px
- 圆角说明：如果图片需要圆角，建议圆角数值取图片尺寸1/4，例如图片为24*24dp，圆角取6dp
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027621883-6.png)
**进度组件3**

- 必传内容：描述文本，当前进度
- 描述文本：描述信息
- 当前进度：已完成进度条在全部进度条上的相对位置
- 进度条颜色：可自定义颜色
- 节点：支持0-4个节点，起点无节点，进度条将被均分
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027621963-7.png)

## 7、 游戏/应用下载等参考模版

**模版21——IM图文组件 + 进度组件2**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027813929-1.png)
**OS3示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761124126868-%E6%9B%B4%E6%96%B0%E5%90%8E%E5%9B%BE%E7%89%879.png)
**Flip外屏示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027813981-3.png)
**IM图文组件 + 进度组件2**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027813976-4.png)
**IM图文组件**

- 必传内容：头像图、主要文本、次要文本
- 头像图：正方形图片，建议像素不小于224*224px
- 圆角说明：如果图片需要圆角，建议圆角数值取图片尺寸1/4，例如图片为48*48dp，圆角取12dp
- 主要文本：关键信息
- 次要文本：补充描述
- 应用图标：
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027814035-5.png)
**进度组件2**

- 必传内容：当前进度，进度条颜色
- 当前进度：已完成进度条在全部进度条上的相对位置
- 进度条颜色：已完成进度条的颜色，推荐和应用色相近的颜色

## 8、 任务/家居进程等参考模版

**模版22——新图文组件 + 识别图文组件1 + 进度组件3**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027952183-1.png)
**OS3示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027952119-2.png)
**Flip外屏示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027952086-3.png)
**新图文组件 + 识别图文组件1 + 进度组件3**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027952107-4.png)
**新图文组件**

- 必传内容：图片、主要文本、次要文本1
- 图片：正方形图片，建议像素不小于224*224px
- 圆角说明：如果图片需要圆角，建议圆角数值取图片尺寸1/4，例如图片为48*48dp，圆角取12dp
- 主要文本：关键信息
- 次要文本：补充描述
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027952119-5.png)
**识别图形组件1**

- 系统默认取桌面图标
- 支持自定义，正方形图片，建议像素不小于96*96px
- 圆角说明：如果图片需要圆角，建议圆角数值取图片尺寸1/4，例如图片为24*24dp，圆角取6dp
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027952218-6.png)
**进度组件3**

- 必传内容：描述文本，当前进度
- 描述文本：描述信息
- 当前进度：已完成进度条在全部进度条上的相对位置
- 进度条颜色：可自定义颜色
- 节点：支持0-4个节点，起点无节点，进度条将被均分
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761027952434-7.png)

## 9、附录1-文本组件说明

（1）间隔符号：主要文本和次要文本都具有的能力，用于在视觉上增强区分，可以控制间隔符号的显隐

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761028102137-1.png)
（2）超长规则：

- 主要文本：超长时会截断显示为“...”，截断顺序依次为补充文本➡️主要文本1和主要文本2➡️特殊标签，注意合理使用文本数量和控制字数
- 次要文本：超长时会截断显示为“...”，截断顺序依次次要文本1和次要文本2➡️功能图标，注意合理使用文本数量和控制字数
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761028102172-2.png)

## 10、 附录2-文本组件说明

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761028102306-3.png)
**前进图形规则描述**

- 素材尺寸60*47dp（红色范围）
- 为保证清晰度，导出尺寸不小于4x（即240*188px）
- 图片大小小于100kb
- 图形主体推荐放置区域（绿色范围），放置区域尺寸44*39dp，图形主体横向居中放置，竖向与绿色区域底边对齐
- 前进图形图示：
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761028102160-4.png)
**中间节点&目标节点规则描述：**

- 素材尺寸30*47dp（红色范围）
- 为保证清晰度，导出尺寸不小于4x（即120*188px）
- 图片大小小于100kb
- 图形主体推荐放置区域（绿色范围）
- 节点到达前：
- 节点到达后：
- 中间节点图示：
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761028102277-5.png)
- 目标节点图示：
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761028102277-6.png)