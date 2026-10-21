<?php

require_once __DIR__ . '/fqcls.php';
$externalLinkUrl = FqCls::externalLinkUrl();

function fanqie_detail_escape($value)
{
    return htmlspecialchars((string) $value, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
}

function fanqie_detail_chapter_navigation($previousUrl, $nextUrl)
{
    if ($previousUrl === '' && $nextUrl === '') {
        return;
    }
    ?>
    <nav class="chapter-navigation" aria-label="章节导航">
        <?php if ($previousUrl !== ''): ?>
            <a href="<?= $previousUrl ?>">‹ 上5章</a>
        <?php endif; ?>
        <?php if ($nextUrl !== ''): ?>
            <a class="next-chapters" href="<?= $nextUrl ?>">下5章 ›</a>
        <?php endif; ?>
    </nav>
    <?php
}

$bookId = isset($_GET['bookId']) ? trim((string) $_GET['bookId']) : '';
$bookTitle = isset($_GET['title']) ? trim((string) $_GET['title']) : '';
$chapterIndex = isset($_GET['chapter']) ? filter_var($_GET['chapter'], FILTER_VALIDATE_INT) : false;
$error = '';
$chapters = array();
$selectedChapters = array();
$contents = array();

if (!preg_match('/^\d{1,32}$/', $bookId) || $chapterIndex === false || $chapterIndex < 0) {
    http_response_code(400);
    $error = '书籍或章节参数无效';
} else {
    try {
        $chapters = FqCls::getChapters($bookId);
        if (!isset($chapters[$chapterIndex])) {
            throw new InvalidArgumentException('请求的章节不在目录范围内');
        }
        $selectedChapters = array_slice($chapters, $chapterIndex, 5);
        if (count($selectedChapters) === 0) {
            throw new RuntimeException('没有可预览的章节');
        }
        $itemIds = array();
        foreach ($selectedChapters as $chapter) {
            $itemIds[] = $chapter['itemId'];
        }
        $contents = FqCls::getChapterContents($bookId, $itemIds);
    } catch (Exception $exception) {
        http_response_code($exception instanceof InvalidArgumentException ? 400 : 502);
        $error = $exception->getMessage();
    }
}

$selectedTitle = isset($selectedChapters[0]['title']) && $selectedChapters[0]['title'] !== ''
    ? $selectedChapters[0]['title'] : '章节正文';
$previousChapterUrl = '';
$nextChapterUrl = '';
if ($chapterIndex !== false && isset($chapters[$chapterIndex])) {
    $chapterBaseUrl = 'detail.php?bookId=' . rawurlencode($bookId)
        . '&amp;title=' . rawurlencode($bookTitle) . '&amp;chapter=';
    if ($chapterIndex >= 5) {
        $previousChapterUrl = $chapterBaseUrl . ($chapterIndex - 5);
    }
    if ($chapterIndex + 5 < count($chapters)) {
        $nextChapterUrl = $chapterBaseUrl . ($chapterIndex + 5);
    }
}
?>
<!doctype html>
<html lang="zh-CN">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title><?= fanqie_detail_escape($selectedTitle) ?> - <?= fanqie_detail_escape($bookTitle) ?></title>
    <style>
        :root { font-family: system-ui, -apple-system, "Segoe UI", sans-serif; color: #302b29; background: #f7f5f2; }
        * { box-sizing: border-box; }
        body { margin: 0; }
        main { width: min(900px, calc(100% - 32px)); margin: 0 auto; padding: 24px 0 56px; }
        a { color: #8d382b; }
        .page-top { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px 16px; margin-bottom: 14px; }
        .back { text-decoration: none; }
        .external-link { flex: 0 0 auto; font-size: 14px; }
        .page-actions { display: contents; }
        .chapter-navigation { display: flex; gap: 16px; font-size: 14px; }
        .chapter-navigation .next-chapters { margin-left: auto; }
        .page-top .chapter-navigation { width: 100%; justify-content: space-between; }
        .bottom-navigation { display: flex; justify-content: center; margin-top: 18px; padding: 14px 18px; background: #fff; border: 1px solid #e8e2dd; border-radius: 14px; }
        .bottom-navigation .chapter-navigation { width: 100%; justify-content: space-between; }
        header, article { background: #fff; border: 1px solid #e8e2dd; border-radius: 14px; }
        header { padding: 18px 20px; margin-bottom: 12px; }
        h1 { margin: 0 0 6px; font-size: 23px; }
        .meta { color: #766d68; font-size: 13px; }
        .chapters { display: grid; gap: 12px; }
        article { padding: 20px; }
        h2 { margin: 0 0 14px; color: #923a2d; font-size: 19px; }
        .chapter-body { white-space: pre-wrap; line-height: 1.9; font-size: 17px; overflow-wrap: anywhere; }
        .error { color: #a52222; white-space: pre-wrap; }
        @media (max-width: 560px) { main { width: calc(100% - 20px); padding-top: 16px; } header, article { padding: 15px; } }
    </style>
</head>
<body>
<main>
    <div class="page-top">
        <a class="back" href="list.php?bookId=<?= rawurlencode($bookId) ?>&amp;title=<?= rawurlencode($bookTitle) ?>#chapter-<?= (int) $chapterIndex ?>">‹ 返回章节目录</a>
        <div class="page-actions">
            <?php if ($externalLinkUrl !== ''): ?>
                <a class="external-link" href="<?= fanqie_detail_escape($externalLinkUrl) ?>" target="_blank" rel="noopener noreferrer">外部链接</a>
            <?php endif; ?>
            <?php fanqie_detail_chapter_navigation($previousChapterUrl, $nextChapterUrl); ?>
        </div>
    </div>
    <header>
        <h1><?= fanqie_detail_escape($bookTitle) ?></h1>
        <div class="meta">从“<?= fanqie_detail_escape($selectedTitle) ?>”开始，显示本章及后续最多 4 章</div>
    </header>
    <?php if ($error !== ''): ?>
        <p class="error"><?= fanqie_detail_escape($error) ?></p>
    <?php else: ?>
        <section class="chapters">
            <?php foreach ($selectedChapters as $chapter): ?>
                <?php
                $itemId = $chapter['itemId'];
                $chapterContent = isset($contents[$itemId]) ? $contents[$itemId] : array();
                ?>
                <article>
                    <h2><?= fanqie_detail_escape($chapter['title'] !== '' ? $chapter['title'] : '未命名章节') ?></h2>
                    <?php if (isset($chapterContent['error']) && $chapterContent['error'] !== null): ?>
                        <div class="error">本章内容暂时无法获取：<?= fanqie_detail_escape($chapterContent['error']) ?></div>
                    <?php elseif (isset($chapterContent['content']) && $chapterContent['content'] !== ''): ?>
                        <div class="chapter-body"><?= fanqie_detail_escape($chapterContent['content']) ?></div>
                    <?php else: ?>
                        <div class="error">本章内容暂时无法获取</div>
                    <?php endif; ?>
                </article>
            <?php endforeach; ?>
        </section>
    <?php endif; ?>
    <?php if ($previousChapterUrl !== '' || $nextChapterUrl !== ''): ?>
        <div class="bottom-navigation">
            <?php fanqie_detail_chapter_navigation($previousChapterUrl, $nextChapterUrl); ?>
        </div>
    <?php endif; ?>
</main>
</body>
</html>
