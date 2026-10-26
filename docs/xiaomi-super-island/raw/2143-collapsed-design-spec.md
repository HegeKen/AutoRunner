# 摘要态设计规范

> 官方原文: https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2143
> 更新时间: 1761200403564
> 本文件由页面内嵌 JSON（__NEXT_DATA__.props.pageProps.defaultContent）转换而来，仅用于离线查阅。


# 一、大岛

大岛容器以摄像头左右分A、B两个区域，A区有2种组件，B区支持9种组件（包含空的情况），共支持9种排列组合。

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761028453311-1.png)

## 1、 手电筒等参考模版

**模版1——图文组件1 + 空**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761028624910-image%20(23).png)
**示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761028633922-image%20(24).png)
**图文组件1 + 空**
**A区组件：图文组件1**
必传内容：图标或正文大字二选一，不能为空
图标部分

- 可上传静态图标、应用图标或动态图标，兜底取应用图标
- 正方形，建议像素不小于88*88px
字体部分

- 大字为主要文本，前置、后置小字为辅助文本
- 保持文本简短，过长文本会影响阅读效率和美观度，建议总字数不超过4个中文字
- 超长效果：大字变成小字，若仍超长会裁切，无滚动
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761028641368-image%20(25).png)
- 窄字体：支持数字、字母，需要传相关参数
- 强调色：可自定义颜色，大小字颜色一致
**B区组件：为空**

## 2、专注模式/航班等参考模版

**模版2——图文组件1 + 文本组件**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761028774775-1.png)
**示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761028774765-2.png)
**图文组件1 + 文本组件**
**A区组件：图文组件1**
必传内容：图标或正文大字二选一，不能为空
图标部分

- 可上传静态图标、应用图标或动态图标，兜底取应用图标
- 正方形，建议像素不小于88*88px
字体部分

- 大字为主要文本，前置、后置小字为辅助文本
- 保持文本简短，过长文本会影响阅读效率和美观度，建议总字数不超过4个中文字
- 超长效果：大字变成小字，若仍超长会裁切，无滚动
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761028774771-3.png)
- 窄字体：支持数字、字母，需要传相关参数
- 强调色：可自定义颜色，大小字颜色一致
**B区组件：文本组件**
必传内容：正文大字

- 谨慎使用多段文本，建议最多2段文本，即前置小字+大字或大字+后置小字
- 大字为主要文本，前置、后置小字为辅助文本
- 保持文本简短，过长文本会影响阅读效率和美观度，建议总字数不超过4个中文字
- 超长效果：大字变成小字，若仍超长会裁切，无滚动
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761028774757-4.png)
- 窄字体：支持数字、字母，需要传相关参数
- 强调色：可自定义颜色，大小字颜色一致

## 3、 设备连接等参考模版

**模版3——图文组件1 + 图文组件2**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761028923991-1.png)
**示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761028924012-2.png)
**图文组件1 + 图文组件2**
**A区组件：图文组件1**
必传内容：图标或正文大字二选一，不能为空
图标部分

- 可上传静态图标、应用图标或动态图标，兜底取应用图标
- 正方形，建议像素不小于88*88px
字体部分

- 大字为主要文本，前置、后置小字为辅助文本
- 保持文本简短，过长文本会影响阅读效率和美观度，建议总字数不超过4个中文字
- 超长效果：大字变成小字，若仍超长会裁切，无滚动
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761028924077-3.png)
- 窄字体：支持数字、字母，需要传相关参数
- 强调色：可自定义颜色，大小字颜色一致
**B区组件：**
必传内容：大字和图标
图标部分

- 可选择静态图标、应用图标或动态图标
- 支持更宽的图形，最大图形宽高比约为1.45:1，建议像素不少于128*88px
字体部分

- 谨慎使用多段文本，建议最多2段文本，即前置小字+大字或大字+后置小字
- 大字为主要文本，前置、后置小字为辅助文本
- 保持文本简短，过长文本会影响阅读效率和美观度，建议总字数不超过4个中文字
- 超长效果：大字变成小字，若仍超长会裁切，无滚动
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761028923994-4.png)
- 窄字体：支持数字、字母，需要传相关参数
- 强调色：可自定义颜色，大小字颜色一致

## 4、 值机等参考模版

**模版4——图文组件1 + 图文组件3**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761029059360-1.png)
**示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761029059357-2.png)
**图文组件1 + 图文组件3**
**A区组件：图文组件1**
必传内容：图标或正文大字二选一，不能为空
图标部分

- 可上传静态图标、应用图标或动态图标，兜底取应用图标
- 正方形，建议像素不小于88*88px
字体部分

- 大字为主要文本，前置、后置小字为辅助文本
- 保持文本简短，过长文本会影响阅读效率和美观度，建议总字数不超过4个中文字
- 超长效果：大字变成小字，若仍超长会裁切，无滚动
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761029059366-3.png)
- 窄字体：支持数字、字母，需要传相关参数
- 强调色：可自定义颜色，大小字颜色一致
**B区组件：**
必传内容：大字和图标
图标部分

- 可选择静态图标、应用图标或动态图标
- 支持更宽的图形，最大图形宽高比约为1.45:1，建议像素不少于128*88px
字体部分

- 保持文本简短，过长文本会影响阅读效率和美观度，建议总字数不超过4个中文字
- 超长效果：大字变成小字，若仍超长会裁切，无滚动
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761029059352-4.png)
- 强调色：可自定义颜色，大小字颜色一致

## 5、 录音/上传等参考模版

**模版5——图文组件1 + 进度文本组件**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761124493745-%E6%9B%B4%E6%96%B0%E5%90%8E%E5%9B%BE%E7%89%8710.png)
**示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761124532149-%E6%9B%B4%E6%96%B0%E5%90%8E%E5%9B%BE%E7%89%8711.png)
**A区组件：图文组件1**
必传内容：图标或正文大字二选一，不能为空
图标部分

- 可上传静态图标、应用图标或动态图标，兜底取应用图标
- 正方形，建议像素不小于88*88px
字体部分

- 大字为主要文本，前置、后置小字为辅助文本
- 保持文本简短，过长文本会影响阅读效率和美观度，建议总字数不超过4个中文字
- 超长效果：大字变成小字，若仍超长会裁切，无滚动
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761029176547-3.png)
- 窄字体：支持数字、字母，需要传相关参数
- 强调色：可自定义颜色，大小字颜色一致
**B区组件：**
必传内容：进度信息
进度部分

- 当前进度：当前进度参数值
- 可自定义已完成进度条和未完成进度条的颜色
字体部分

- 保持文本简短，过长文本会影响阅读效率和美观度，建议总字数不超过4个中文字
- 超长效果：大字变成小字，若仍超长会裁切，无滚动
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761029176526-4.png)
- 强调色：可自定义颜色，大小字颜色一致

## 6、 电话/倒计时等参考模版

**模版6——图文组件1 + 等宽数字文本组件**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761029295918-1.png)
**示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761029295913-2.png)
**图文组件1 + 等宽数字文本组件**

**A区组件：图文组件1**
必传内容：图标或正文大字二选一，不能为空
图标部分

- 可上传静态图标、应用图标或动态图标，兜底取应用图标
- 正方形，建议像素不小于88*88px
字体部分

- 大字为主要文本，前置、后置小字为辅助文本
- 保持文本简短，过长文本会影响阅读效率和美观度，建议总字数不超过4个中文字
- 超长效果：大字变成小字，若仍超长会裁切，无滚动
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761029295932-3.png)
- 窄字体：支持数字、字母，需要传相关参数
- 强调色：可自定义颜色，大小字颜色一致
**B区组件：等宽数字组件**
必传内容：等宽数字
等宽数字：每个数字是等宽的

- 支持数字或计时器
- 通常用于倒计时/正计时等有秒数级变化的情况，或视觉上需要工整排布
- 支持自定义颜色
后置小字：

- 保持文本简短，过长文本会影响阅读效率和美观度
- 强调色：可自定义颜色，大小字颜色一致

## 7、 导航/打车等参考模版

**模版7——图文组件1 + 定宽数字文本组件**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761029402748-1.png)
**示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761029402718-2.png)
**图文组件1 + 定宽数字文本组件**

**A区组件：图文组件1**
必传内容：图标或正文大字二选一，不能为空
图标部分

- 可上传静态图标、应用图标或动态图标，兜底取应用图标
- 正方形，建议像素不小于88*88px
字体部分

- 大字为主要文本，前置、后置小字为辅助文本
- 保持文本简短，过长文本会影响阅读效率和美观度，建议总字数不超过4个中文字
- 超长效果：大字变成小字，若仍超长会裁切，无滚动
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761029402746-3.png)
- 窄字体：支持数字、字母，需要传相关参数
- 强调色：可自定义颜色，大小字颜色一致 B区组件：定宽数字组件 必传内容：数字 数字部分：
- 数字在固定宽度的区域内显示，该区域最多支持显示3位数，最少1位数
- 超长后压缩显示为“...”
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761029402728-4.png)
后置小字：

- 保持文本简短，过长文本会影响阅读效率和美观度
- 强调色：可自定义颜色，大小字颜色一致

## 8、 音乐/设备电量等参考模版

**模版8——图文组件1 + 大图组件**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761029501737-1.png)
**示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761029501714-2.png)
**图文组件1 + 大图组件**

**A区组件：图文组件1**
必传内容：图标或正文大字二选一，不能为空
图标部分

- 可上传静态图标、应用图标或动态图标，兜底取应用图标
- 正方形，建议像素不小于88*88px
字体部分

- 大字为主要文本，前置、后置小字为辅助文本
- 保持文本简短，过长文本会影响阅读效率和美观度，建议总字数不超过4个中文字
- 超长效果：大字变成小字，若仍超长会裁切，无滚动
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761029501708-3.png)
- 窄字体：支持数字、字母，需要传相关参数
- 强调色：可自定义颜色，大小字颜色一致
**B区组件：大图组件**
必传内容：大图

- 支持静态、动态大图
- 支持更宽的图形，最大图形宽高比约为2.2:1，建议像素不少于192*88px

## 9、 导航（红绿灯）等参考模版

**模版9——图文组件5+图文组件6**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761030059694-1.png)
**示例**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761030059689-2.png)
**图文组件5+图文组件6**

**A区组件：图文组件5**
必传内容：图片，大字

- 图片：正方形，建议像素不少于88*88px
- 整体定宽文本-大字：
- 整体定宽文本-小字：
![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761030059731-3.png)
**B区组件：图文组件6**
必传内容：数字，图片

- 数字：最多支持3个数字
- 图片：正方形，建议像素不少于88*88px

# 二、小岛

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761124592583-%E6%9B%B4%E6%96%B0%E5%90%8E%E5%9B%BE%E7%89%8712.png)
小岛容器展示内容：

- 优先取业务上传的图标组件
- 若业务无上传，取大岛A区左侧的图标
- 若条件1、2都不满足，取应用图标

## 1、 手电筒/录音等参考模版

**模版1——图标组件**
图标组件

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761030236743-1.png)
**说明**

- 图标可选应用图标、静态图标
- 必须为正方形，建议像素不少于88*88px

## 2、 上传/下载等参考模版

**模版2——图标组合组件**
**图标组合组件**

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761124631183-%E6%9B%B4%E6%96%B0%E5%90%8E%E5%9B%BE%E7%89%8713.png)
**说明**
图标

- 图片：必须为正方形，建议像素不少于88*88px
- 进度条：支持自定义颜色

## 3、 倒计时/红绿灯等参考模版

**模版3——图标文本组件**
图标文本组件

![](https://cdn.cnbj1.fds.api.mi-img.com/hyperos-developer-public/website/1761030236743-3.png)
**说明**
使用图文组件 6

- 在imageTextInfoRight中type使用6即可
- 数字：最多支持3个数字
- 图片：必须为正方形，建议像素不少于88*88px

## 4、岛字符数量规范说明

为保障岛上信息清晰与交互体验，小米超级岛各状态字符数量需遵循以下规范进行设计：
[《岛字符数量规范说明》](https://mag-admin.cnbj1.mi-fds.com/mag-admin/island/岛字符数量规范说明.pdf)
