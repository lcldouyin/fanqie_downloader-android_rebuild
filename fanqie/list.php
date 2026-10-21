<?php

require_once __DIR__ . '/fqcls.php';
$externalLinkUrl = FqCls::externalLinkUrl();

function fanqie_list_escape($value)
{
    return htmlspecialchars((string) $value, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
}

$bookId = isset($_GET['bookId']) ? trim((string) $_GET['bookId']) : '';
$bookTitle = isset($_GET['title']) ? trim((string) $_GET['title']) : '';
$error = '';
$chapters = array();

if (!preg_match('/^\d{1,32}$/', $bookId)) {
    http_response_code(400);
    $error = 'Book ID 格式无效';
} else {
    try {
        $chapters = FqCls::getChapters($bookId);
        if ($bookTitle === '') {
            $bookTitle = $bookId;
        }
    } catch (Exception $exception) {
        http_response_code(502);
        $error = $exception->getMessage();
    }
}
?>
<!doctype html>
<html lang="zh-CN">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title><?= fanqie_list_escape($bookTitle !== '' ? $bookTitle : '章节目录') ?> - 章节目录</title>
    <style>
        :root { font-family: system-ui, -apple-system, "Segoe UI", sans-serif; color: #302b29; background: #f7f5f2; }
        * { box-sizing: border-box; }
        body { margin: 0; }
        main { width: min(900px, calc(100% - 32px)); margin: 0 auto; padding: 24px 0 56px; }
        a { color: #8d382b; }
        .page-top { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
        .back { text-decoration: none; }
        .external-link { flex: 0 0 auto; font-size: 14px; }
        .panel, .chapter { background: #fff; border: 1px solid #e8e2dd; border-radius: 14px; }
        .panel { padding: 20px; }
        h1 { margin: 0; font-size: 25px; }
        .meta { margin: 8px 0 18px; color: #766d68; font-size: 13px; }
        .error { color: #a52222; white-space: pre-wrap; }
        ol { display: grid; gap: 8px; margin: 0; padding-left: 28px; }
        li { padding-left: 3px; }
        .chapter { padding: 12px 14px; }
        .chapter a { display: block; color: inherit; text-decoration: none; line-height: 1.55; }
        .chapter a:hover { color: #a83f2e; }
        @media (max-width: 560px) { main { width: calc(100% - 20px); padding-top: 16px; } .panel { padding: 14px; } }
    </style>
</head>
<body>
<main>
    <div class="page-top">
        <a class="back" href="index.php">‹ 返回搜索</a>
        <?php if ($externalLinkUrl !== ''): ?>
            <a class="external-link" href="<?= fanqie_list_escape($externalLinkUrl) ?>" target="_blank" rel="noopener noreferrer">外部链接</a>
        <?php endif; ?>
    </div>
    <section class="panel">
        <h1><?= fanqie_list_escape($bookTitle !== '' ? $bookTitle : '章节目录') ?></h1>
        <?php if ($error !== ''): ?>
            <p class="error"><?= fanqie_list_escape($error) ?></p>
        <?php else: ?>
            <p class="meta">Book ID：<?= fanqie_list_escape($bookId) ?> · 共 <?= count($chapters) ?> 章</p>
            <?php if (count($chapters) === 0): ?>
                <p>没有获取到章节目录。</p>
            <?php else: ?>
                <ol>
                    <?php foreach ($chapters as $index => $chapter): ?>
                        <li class="chapter" id="chapter-<?= (int) $index ?>">
                            <a href="detail.php?bookId=<?= rawurlencode($bookId) ?>&amp;title=<?= rawurlencode($bookTitle) ?>&amp;chapter=<?= $index ?>">
                                <?= fanqie_list_escape($chapter['title'] !== '' ? $chapter['title'] : '未命名章节') ?>
                            </a>
                        </li>
                    <?php endforeach; ?>
                </ol>
            <?php endif; ?>
        <?php endif; ?>
    </section>
</main>
</body>
</html>
