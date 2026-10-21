<?php

require_once __DIR__ . '/fqcls.php';

if (isset($_SERVER['REQUEST_METHOD']) && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $payload = json_decode(file_get_contents('php://input'), true);
    if (!is_array($payload)) {
        FqCls::jsonResponse(array('error' => '请求 JSON 无效'), 400);
    }

    try {
        $action = isset($payload['action']) ? (string) $payload['action'] : '';
        if ($action !== 'search') {
            FqCls::jsonResponse(array('error' => '未知操作'), 400);
        }
        $keyword = isset($payload['keyword']) ? trim((string) $payload['keyword']) : '';
        if ($keyword === '' || mb_strlen($keyword, 'UTF-8') > 100) {
            FqCls::jsonResponse(array('error' => '请输入不超过 100 个字符的搜索词'), 400);
        }
        FqCls::jsonResponse(array('books' => FqCls::search($keyword)));
    } catch (Exception $error) {
        FqCls::jsonResponse(array('error' => $error->getMessage()), 502);
    }
}
$externalLinkUrl = FqCls::externalLinkUrl();
?>
<!doctype html>
<html lang="zh-CN">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>番茄小说搜索</title>
    <style>
        :root {
            color-scheme: light;
            font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
            color: #302b29;
            background: #f7f5f2;
        }
        * { box-sizing: border-box; }
        body { margin: 0; }
        main { width: min(900px, calc(100% - 32px)); margin: 0 auto; padding: 32px 0 56px; }
        .page-top { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 22px; }
        h1 { margin: 0; font-size: 28px; }
        .external-link { flex: 0 0 auto; color: #8d382b; font-size: 14px; }
        form, .book {
            background: #fff;
            border: 1px solid #e8e2dd;
            border-radius: 14px;
        }
        form { display: flex; gap: 10px; padding: 16px; }
        input, button { font: inherit; }
        input[type="search"] {
            flex: 1; min-width: 0; padding: 12px 14px; border: 1px solid #d6cec8;
            border-radius: 9px;
        }
        button {
            border: 0; border-radius: 9px; padding: 11px 16px; cursor: pointer;
            background: #a83f2e; color: white;
        }
        button:disabled { opacity: .5; cursor: wait; }
        .status { min-height: 24px; margin: 14px 2px; color: #6c625c; }
        .error { color: #a52222; }
        .results { display: grid; gap: 10px; }
        .book { display: block; padding: 16px; color: inherit; text-decoration: none; }
        .book:hover { border-color: #bf8a7c; }
        .book strong { display: block; margin-bottom: 6px; font-size: 17px; }
        .meta { color: #766d68; font-size: 13px; }
        .description { display: block; margin-top: 8px; color: #625a55; line-height: 1.5; }
        @media (max-width: 560px) {
            main { width: calc(100% - 20px); padding-top: 22px; }
            form { padding: 12px; }
            form button { padding-inline: 12px; }
        }
    </style>
</head>
<body>
<main>
    <div class="page-top">
        <h1>番茄小说搜索</h1>
        <?php if ($externalLinkUrl !== ''): ?>
            <a class="external-link" href="<?= htmlspecialchars($externalLinkUrl, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8') ?>" target="_blank" rel="noopener noreferrer">外部链接</a>
        <?php endif; ?>
    </div>
    <form id="search-form">
        <input id="keyword" type="search" maxlength="100" placeholder="输入书名或搜索关键词" required>
        <button id="search-button" type="submit">搜索书籍</button>
    </form>
    <div id="search-status" class="status" role="status"></div>
    <section id="search-results" class="results" aria-live="polite"></section>
</main>
<script>
    (function () {
        var searchForm = document.getElementById('search-form');
        var searchButton = document.getElementById('search-button');
        var searchStatus = document.getElementById('search-status');
        var searchResults = document.getElementById('search-results');

        function addText(parent, tag, text, className) {
            var node = document.createElement(tag);
            node.textContent = text || '';
            if (className) node.className = className;
            parent.appendChild(node);
            return node;
        }

        searchForm.addEventListener('submit', function (event) {
            event.preventDefault();
            var keyword = document.getElementById('keyword').value.trim();
            if (!keyword) return;
            searchButton.disabled = true;
            searchStatus.textContent = '正在搜索…';
            searchStatus.classList.remove('error');
            searchResults.replaceChildren();
            fetch('index.php', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({action: 'search', keyword: keyword})
            }).then(function (response) {
                return response.json().then(function (body) {
                    if (!response.ok) throw new Error(body.error || '搜索失败');
                    return body;
                });
            }).then(function (data) {
                var books = Array.isArray(data.books) ? data.books : [];
                searchStatus.textContent = books.length
                    ? '找到 ' + books.length + ' 本书，点击书名查看章节目录'
                    : '没有找到相关书籍，请尝试其他关键词';
                books.forEach(renderBook);
            }).catch(function (error) {
                searchStatus.textContent = error.message;
                searchStatus.classList.add('error');
            }).finally(function () {
                searchButton.disabled = false;
            });
        });

        function renderBook(book) {
            var card = document.createElement('a');
            card.className = 'book';
            card.href = 'list.php?bookId=' + encodeURIComponent(book.bookId)
                + '&title=' + encodeURIComponent(book.title || book.bookId);
            addText(card, 'strong', book.title || book.bookId);
            addText(card, 'span',
                (book.author || '作者未知') + ' · ' + (book.category || '未分类')
                + ' · 评分 ' + (book.score || 'N/A'), 'meta');
            addText(card, 'span', 'Book ID：' + book.bookId, 'meta');
            if (book.description) addText(card, 'span', book.description, 'description');
            searchResults.appendChild(card);
        }
    }());
</script>
</body>
</html>
