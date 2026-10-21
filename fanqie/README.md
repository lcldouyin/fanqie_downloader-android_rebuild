# 番茄小说 PHP 章节预览

独立的 PHP 网页版：在 `index.php` 搜索书籍，在 `list.php` 查看书名和章节目录，点击章节后由 `detail.php` 显示该章及后续最多 4 章。不提供下载功能。所有 PHP、HTML、CSS 和 JavaScript 文件均在本目录内，不依赖 Android 工程中的文件。

## 运行

要求 PHP 7.2 或更新版本，并启用 cURL、OpenSSL、JSON、mbstring 和 zlib 扩展。

```powershell
cd fanqie
php -S 127.0.0.1:8000 -t .
```

浏览器打开 `http://127.0.0.1:8000`。搜索请求由 `index.php` 自身处理；搜索结果进入 `list.php` 章节目录；选择一章后进入 `detail.php` 阅读本章及接下来的最多 4 章。

三个页面右上角的外部链接地址统一配置在 `config/config.php` 的 `top_right_link_url`。默认值为空字符串，不显示链接；可设置为完整的 `http://` 或 `https://` 地址，或以 `../` 开头的相对 URL，链接会在新窗口打开。

核心 PHP 逻辑封装在 `fqcls.php` 的 `FqCls` 类中。固定接口地址、请求参数和 User-Agent 配置在 `config/config.php`；Cookie 单独保存在 `cookies/fq.txt`，每行填写一条 Cookie。各 PHP 页面/API 可直接访问。Apache 和 IIS 可分别使用 `cookies/.htaccess`、`cookies/web.config` 禁止 Web 访问 Cookie；PHP 内置服务器不支持这两类规则，因此不要在不可信网络环境运行内置服务器，也不要将 Cookie 目录暴露给公网。

章节正文按页批量读取（每次最多 5 章），签名服务签署完整阅读 URL 后，PHP 以 HTTP GET 请求 `/reading/reader/batch_full/v`；`item_ids` 在 URL 查询参数中使用逗号分隔。正文读取需要一个可用的本地签名服务，地址配置在 `config/config.php`。PHP 会以 `application/x-www-form-urlencoded` 为 Content-Type，将完整阅读 URL 作为 POST body 发送给签名服务；服务应返回 JSON：

```json
{
  "url": "https://api5-normal-sinfonlinea.fqnovel.com/...",
  "headers": {"header-name": "header-value"},
  "key": "32位十六进制AES密钥"
}
```

该签名服务不是本目录中的实现，且不会由 PHP 网页自动启动。如需更换固定参数，请编辑 `config/config.php`；Cookie 仅从 `cookies/fq.txt` 读取。

所有 PHP cURL HTTPS 请求按要求关闭证书和主机名校验。请只在可信的本机环境运行；不要将包含账户 Cookie 的服务暴露到公网。
