# VideoSnatch 使用说明（给非程序员看的版本）

一个安卓小工具：把 **抖音 / 皮皮虾 / X(Twitter)** 的分享链接粘进去，点一下就能拿到视频并保存到手机相册。

**最关键的一点先说在前面：**
解析方式不是写死在 App 里的，而是写在一个叫 `rules.json` 的文件里。
接口哪天失效了，**你只要改这一个文件**，App 下次打开会自动拉新规则，**不用重新编译、不用重装 App、不用碰代码**。

---

## 一、第一次安装（照着做就行，全程在网页上点）

### 第 1 步：建一个 GitHub 仓库

1. 打开 https://github.com/new （要登录你的账号）
2. Repository name 填：`VideoSnatch`
3. 选 **Public**（必须是 Public，不然手机拿不到规则文件）
4. **不要**勾 "Add a README file"（保持空仓库最好操作）
5. 点 **Create repository**

### 第 2 步：把项目文件传上去

建好后页面会显示一个空仓库，点页面上方的 **Add file → Upload files**。

> ⚠️ 关键：要上传的是 **VideoSnatch 文件夹里面**的内容，不是文件夹本身。
> 请双击打开 `VideoSnatch` 文件夹 → `Ctrl+A` 全选 → 一起拖到网页的上传框里。
> 上传后页面上应该能看到 `.github`、`app`、`rules.json`、`README.md` 这几项**直接在根目录**。
> 如果你看到的是一个叫 `VideoSnatch` 的文件夹，说明多套了一层，删掉重传。

拖完等进度条走完，页面下方填 `first upload`，点绿色的 **Commit changes**。

> 如果你的浏览器拖文件夹没反应：装个 GitHub Desktop（https://desktop.github.com/），
> 用它 Clone 刚建的仓库，把 `VideoSnatch` 文件夹里的内容复制进去，填个说明点 Commit → Push origin。效果一样。

### 第 3 步：等 GitHub 帮你编译 APK

传完文件后，点仓库上方的 **Actions** 标签页。
会看到一个正在跑的任务（黄色圆点），点进去能看到进度，**大约 3～8 分钟**。

跑完之后（绿勾）有两处可以拿到 APK：

- **推荐**：仓库右侧 **Releases** → 点最新的 `VideoSnatch 自动构建 #1` → 最下面有个 `VideoSnatch.apk`，点它下载（手机浏览器也能直接下）
- 或者：Actions 那次任务的页面最下方 **Artifacts** → `VideoSnatch-APK`

> 以后每次你改文件并 Commit，都会自动重新编译出一版新的 APK。

### 第 4 步：手机上装好

1. 把 APK 传到手机（微信/QQ 传、或者手机浏览器打开 GitHub 的 Releases 页面直接下载）
2. 安装时系统会提示"未知来源"，允许一次即可（不同品牌位置不同，一般在设置里搜"安装未知应用"）
3. 装完打开 App → 点 **规则设置 / 立即更新** → 填你的 **GitHub 用户名**（就是 `/new` 建仓库那个账号名）→ 点 **保存并更新**
4. 顶上显示"规则版本 v1"就说明接通了 ✅

---

## 二、平时怎么用

三种方式任选：

1. **最省事**：在抖音/皮皮虾/X 里点"分享" → 选 **VideoSnatch** → 自动解析
2. 复制链接 → 打开 App → 点 **粘贴** → 自动解析
3. 手动粘贴链接到输入框 → 点 **解析**

解析成功后点 **下载**，通知栏有进度，文件存到 **相册 / Movies / VideoSnatch**。

---

## 三、哪天解析不了了怎么办（重点）

先做个判断：

- 三个平台全挂 → 多半是规则文件没更新成功，去 App 里点"规则设置 / 立即更新"看看
- 只有某一个平台挂 → 那个平台的接口变了，改规则文件

### 方法 A：让我帮你改（最省事）

1. App 里点 **复制日志**，把日志发我
2. 我会给你一份新的 `rules.json` 内容
3. 你在 GitHub 上打开 `rules.json` → 右上角铅笔图标（Edit）→ **全选删掉，粘贴新的** → 点 **Commit changes**
4. 打开 App（或点"立即更新"），就好了。**App 不用重装。**

### 方法 B：自己改（其实也能改）

`rules.json` 里每个平台有 1～3 条"方案"，App 会按顺序一条条试，第一条成功就用它。
常见要改的就是里面的 `url`（接口地址）和 `pattern`/`path`（从返回值里取视频地址的方式）。

---

## 四、rules.json 语法速查（想自己动手时看）

每个平台长这样：

```json
{
  "id": "douyin-web",
  "name": "抖音·网页直取",
  "match": ["douyin\\.com"],
  "steps": [ ... ],
  "output": { "url": "{videoUrl}", "title": "{title}" }
}
```

`steps` 里的每一步都是一个小动作，按顺序执行：

| type | 干什么 | 常用字段 |
|---|---|---|
| `http` | 发一个网络请求，结果存起来 | `url`、`method`、`headers`、`save` |
| `regex` | 从文本里正则抠出内容 | `from`、`pattern`（可写多个，依次试）、`save` |
| `jsonpath` | 从 JSON 里取值，如 `data.item.video.play_addr.url_list[0].url` | `from`、`path`、`save` |
| `replace` | 文本替换（如 `playwm` → `play` 去水印） | `from`、`pattern`、`to` |
| `decode` | 解码：`json_escape` / `url` / `base64` | `from`、`codec` |
| `set` | 设一个变量，或拼接 URL | `value`、`save`、`whenEmpty` |
| `pick` | 从多档清晰度里挑最高码率 | `from`、`key`、`take`、`max` |

- 变量名用 `{xxx}` 引用，比如 `"url": "https://xxx.com/video/{id}"`
- 每一步都可以加 `"optional": true`，表示"失败也没关系，跳过继续"
- `http` 这一步会把返回内容存到 `save` 指定的名字里，同时把最终跳转后的网址存成 `名字_url`
- 想加一个新平台的解析方案：复制一段改改 `match` 和 `steps` 就行，App 会自动多条依次尝试

---

## 五、常见问题

**Q：为什么要 Public 仓库？**
规则文件要让手机免登录下载，私有仓库需要密钥，麻烦。规则里没有任何隐私内容。

**Q：换手机/重装 App 要重新配置吗？**
只要再填一次 GitHub 用户名就行，规则会自动拉下来。

**Q：提示"解析失败"，但链接是对的？**
先点"规则设置 / 立即更新"拉一次最新规则；还是不行就把日志发我。

**Q：新 APK 装不上，提示签名冲突？**
罕见。卸载旧的再装新的即可（视频文件不会丢，它们在 Movies 目录里）。

**Q：X 用不了？**
X 需要能访问其接口，网络环境你自己解决就行，App 本身没有额外限制。

**Q：下载下来的视频有水印吗？**
规则里已经做了 `playwm → play` 的无水印替换，抖音/皮皮虾一般是无水印的；个别情况平台接口只给带水印地址，那就没法去。

**Q：能不能加小红书/快手/B站？**
能。在 `rules.json` 里加一段 `providers` 条目即可（App 代码不用动），把日志发给我，我给你写。
