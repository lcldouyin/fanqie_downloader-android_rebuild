<?php

class FqCls
{
    private static $config = null;

    private static function config()
    {
        if (self::$config === null) {
            $config = require __DIR__ . DIRECTORY_SEPARATOR . 'config'
                . DIRECTORY_SEPARATOR . 'config.php';
            if (!is_array($config)) {
                throw new RuntimeException('config/config.php 必须返回配置数组');
            }
            self::$config = $config;
        }
        return self::$config;
    }

    public static function externalLinkUrl()
    {
        $config = self::config();
        $url = isset($config['top_right_link_url'])
            ? trim((string) $config['top_right_link_url']) : '';
        if ($url === '') {
            return '';
        }
        $parts = parse_url($url);
        $isRelativePath = preg_match('/^\.\.(?:\/|$)/', $url) === 1
            && is_array($parts) && !isset($parts['scheme']) && !isset($parts['host']);
        $isHttpUrl = is_array($parts) && isset($parts['scheme'], $parts['host'])
            && in_array(strtolower($parts['scheme']), array('http', 'https'), true);
        if (!$isRelativePath && !$isHttpUrl) {
            throw new RuntimeException('top_right_link_url 必须是 http/https URL 或以 ../ 开头的相对 URL');
        }
        return $url;
    }

    public static function jsonResponse($data, $status = 200)
    {
        http_response_code($status);
        header('Content-Type: application/json; charset=utf-8');
        header('Cache-Control: no-store');
        echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_INVALID_UTF8_SUBSTITUTE);
        exit;
    }

    private static function curl($url, $method = 'GET', $headers = array(), $body = null,
        $max429Retries = 0)
    {
        for ($attempt = 0; ; $attempt++) {
            $handle = curl_init();
            curl_setopt_array($handle, array(
                CURLOPT_URL => $url,
                CURLOPT_RETURNTRANSFER => true,
                CURLOPT_FOLLOWLOCATION => true,
                CURLOPT_CONNECTTIMEOUT => 20,
                CURLOPT_TIMEOUT => 90,
                CURLOPT_ENCODING => '',
                CURLOPT_SSL_VERIFYPEER => false,
                CURLOPT_SSL_VERIFYHOST => 0,
                CURLOPT_HTTPHEADER => $headers
            ));

            if ($method === 'POST') {
                curl_setopt($handle, CURLOPT_POST, true);
                if ($body !== null) {
                    curl_setopt($handle, CURLOPT_POSTFIELDS, $body);
                }
            }

            $response = curl_exec($handle);
            $status = (int) curl_getinfo($handle, CURLINFO_HTTP_CODE);
            $error = curl_error($handle);
            curl_close($handle);

            if ($response === false) {
                throw new RuntimeException('cURL 请求失败：' . $error);
            }
            if ($status === 429 && $attempt < $max429Retries) {
                usleep(800000 * ($attempt + 1));
                continue;
            }
            if ($status < 200 || $status >= 300) {
                throw new RuntimeException('HTTP 请求失败，状态码：' . $status);
            }
            return $response;
        }
    }

    private static function fetchJson($url, $headers = array(), $max429Retries = 0,
        $method = 'GET', $body = null)
    {
        $responseBody = self::curl($url, $method, $headers, $body, $max429Retries);
        $data = json_decode($responseBody, true);
        if (!is_array($data)) {
            throw new RuntimeException('远端响应不是有效 JSON');
        }
        return $data;
    }

    public static function search($keyword)
    {
        $config = self::config();
        $params = array_merge(
            array('q' => $keyword),
            $config['search_params']
        );
        $url = $config['search_url']
            . '?' . http_build_query($params, '', '&', PHP_QUERY_RFC3986);
        $data = self::fetchJson($url, array(
            'User-Agent: ' . $config['search_user_agent']
        ));
        $items = isset($data['data']['ret_data']) && is_array($data['data']['ret_data'])
            ? $data['data']['ret_data'] : array();

        $books = array();
        foreach ($items as $item) {
            if (!is_array($item) || empty($item['book_id'])) {
                continue;
            }
            $books[] = array(
                'bookId' => (string) $item['book_id'],
                'title' => isset($item['title']) ? (string) $item['title'] : '',
                'author' => isset($item['author']) ? (string) $item['author'] : '',
                'category' => isset($item['category']) ? (string) $item['category'] : '',
                'score' => isset($item['score']) ? (string) $item['score'] : 'N/A',
                'description' => isset($item['abstract']) ? (string) $item['abstract'] : ''
            );
        }
        return $books;
    }

    private static function mapChapterList($items)
    {
        $chapters = array();
        foreach ($items as $item) {
            if (!is_array($item)) {
                continue;
            }
            $itemId = isset($item['itemId']) ? $item['itemId']
                : (isset($item['item_id']) ? $item['item_id'] : '');
            if ($itemId === '') {
                continue;
            }
            $chapters[] = array(
                'itemId' => (string) $itemId,
                'title' => isset($item['title']) ? (string) $item['title'] : '',
                'order' => count($chapters) + 1
            );
        }
        return $chapters;
    }

    private static function extractChapters($data)
    {
        if (!is_array($data)) {
            return array();
        }

        foreach (array('chapterList', 'chapter_list', 'chapters', 'items', 'list', 'item_list') as $key) {
            if (isset($data[$key]) && is_array($data[$key]) && count($data[$key]) > 0) {
                $chapters = self::mapChapterList($data[$key]);
                if (count($chapters) > 0) {
                    return $chapters;
                }
            }
        }

        if (isset($data['chapterListWithVolume']) && is_array($data['chapterListWithVolume'])) {
            $chapters = array();
            foreach ($data['chapterListWithVolume'] as $volume) {
                if (!is_array($volume)) {
                    continue;
                }
                foreach ($volume as $chapter) {
                    if (!is_array($chapter)) {
                        continue;
                    }
                    $itemId = isset($chapter['itemId']) ? $chapter['itemId']
                        : (isset($chapter['item_id']) ? $chapter['item_id'] : '');
                    if ($itemId === '') {
                        continue;
                    }
                    $chapters[] = array(
                        'itemId' => (string) $itemId,
                        'title' => isset($chapter['title']) ? (string) $chapter['title'] : '',
                        'order' => isset($chapter['realChapterOrder'])
                            ? (int) $chapter['realChapterOrder'] : count($chapters) + 1
                    );
                }
            }
            if (count($chapters) > 0) {
                return $chapters;
            }
        }

        if (isset($data['data']) && is_array($data['data'])) {
            return self::extractChapters($data['data']);
        }
        return array();
    }

    private static function extractScriptJson($html, $scriptId)
    {
        $offset = strpos($html, $scriptId);
        if ($offset === false) {
            return null;
        }
        $start = strpos($html, '{', $offset + strlen($scriptId));
        if ($start === false) {
            return null;
        }

        $depth = 0;
        $inString = false;
        $escaped = false;
        $length = strlen($html);
        for ($i = $start; $i < $length; $i++) {
            $char = $html[$i];
            if ($inString) {
                if ($escaped) {
                    $escaped = false;
                } elseif ($char === '\\') {
                    $escaped = true;
                } elseif ($char === '"') {
                    $inString = false;
                }
            } elseif ($char === '"') {
                $inString = true;
            } elseif ($char === '{') {
                $depth++;
            } elseif ($char === '}') {
                $depth--;
                if ($depth === 0) {
                    $decoded = json_decode(substr($html, $start, $i - $start + 1), true);
                    return is_array($decoded) ? $decoded : null;
                }
            }
        }
        return null;
    }

    public static function getChapters($bookId)
    {
        $config = self::config();
        $directoryUrl = $config['directory_url'] . '?'
            . http_build_query(array('bookId' => $bookId), '', '&', PHP_QUERY_RFC3986);
        $lastError = null;
        for ($attempt = 0; $attempt < 2; $attempt++) {
            try {
                if ($attempt > 0) {
                    usleep(300000);
                }
                $response = self::fetchJson($directoryUrl, array(
                    'User-Agent: ' . $config['search_user_agent']
                ));
                $chapters = self::extractChapters(
                    isset($response['data']) ? $response['data'] : null
                );
                if (count($chapters) > 0) {
                    return $chapters;
                }
                $lastError = new RuntimeException('目录接口没有返回章节');
            } catch (Exception $error) {
                $lastError = $error;
            }
        }

        $html = self::curl(
            $config['book_page_url'] . rawurlencode($bookId),
            'GET',
            array('User-Agent: ' . $config['search_user_agent'])
        );
        foreach (array('__NEXT_DATA__', '__INITIAL_STATE__') as $scriptId) {
            $state = self::extractScriptJson($html, $scriptId);
            if (isset($state['page'])) {
                $chapters = self::extractChapters($state['page']);
                if (count($chapters) > 0) {
                    return $chapters;
                }
            }
        }
        throw new RuntimeException($lastError
            ? $lastError->getMessage() : '获取章节目录失败');
    }

    private static function readingParams($bookId)
    {
        $config = self::config();
        return array_merge(
            array('book_id' => $bookId),
            $config['reading_params']
        );
    }

    private static function batchReadingUrl($bookId, $itemIds)
    {
        $config = self::config();
        $params = self::readingParams($bookId);
        $params['novel_text_type'] = '1';
        $params['req_type'] = '1';
        $itemIds = array_map('rawurlencode', $itemIds);
        return $config['reading_batch_url'] . '?'
            . http_build_query($params, '', '&', PHP_QUERY_RFC3986)
            . '&item_ids=' . implode(',', $itemIds);
    }

    private static function decryptContent($encoded, $keyHex)
    {
        if (!is_string($keyHex) || !preg_match('/^[0-9a-fA-F]{32}$/', $keyHex)) {
            throw new RuntimeException('签名服务返回的解密 key 无效');
        }
        $raw = base64_decode($encoded, true);
        if ($raw === false || strlen($raw) < 32 || (strlen($raw) - 16) % 16 !== 0) {
            throw new RuntimeException('章节密文格式无效');
        }
        $iv = substr($raw, 0, 16);
        $ciphertext = substr($raw, 16);
        $plaintext = openssl_decrypt(
            $ciphertext,
            'AES-128-CBC',
            hex2bin($keyHex),
            OPENSSL_RAW_DATA,
            $iv
        );
        if ($plaintext === false) {
            throw new RuntimeException('章节内容解密失败');
        }
        if (substr($plaintext, 0, 2) === "\x1f\x8b") {
            $inflated = gzdecode($plaintext);
            if ($inflated === false) {
                throw new RuntimeException('章节内容解压失败');
            }
            $plaintext = $inflated;
        }
        return $plaintext;
    }

    private static function contentText($html)
    {
        $parts = array();
        if (preg_match_all('/<blk\b[^>]*>(.*?)<\/blk>/is', $html, $matches)) {
            $blocks = $matches[1];
            array_shift($blocks);
            foreach ($blocks as $block) {
                $parts[] = $block;
            }
        } elseif ($html !== '') {
            $parts[] = $html;
        }
        $text = implode("\n", $parts);
        $text = preg_replace('/<br\s*\/?>/i', "\n", $text);
        $text = preg_replace('/<p\b[^>]*>/i', "\n\n", $text);
        $text = preg_replace('/<\/\s*(?:p|div|li|blockquote|h[1-6])\s*>/i', "\n\n", $text);
        $text = strip_tags($text);
        $text = html_entity_decode($text, ENT_QUOTES | ENT_HTML5, 'UTF-8');
        $text = preg_replace('/\n[ \t]*\n(?:[ \t]*\n)+/', "\n\n", $text);
        return trim($text);
    }

    private static function readCookie()
    {
        $cookiePath = __DIR__ . DIRECTORY_SEPARATOR . 'cookies'
            . DIRECTORY_SEPARATOR . 'fq.txt';
        if (!is_file($cookiePath) || !is_readable($cookiePath)) {
            throw new RuntimeException('Cookie 文件不存在或不可读：cookies/fq.txt');
        }
        $cookie = trim(file_get_contents($cookiePath));
        if ($cookie === '' || preg_match('/[\r\n]/', $cookie)) {
            throw new RuntimeException('cookies/fq.txt 必须包含一行有效 Cookie');
        }
        return $cookie;
    }

    private static function signedReaderRequest($readingUrl, $method = 'GET', $body = null)
    {
        $config = self::config();
        $signedBody = self::curl(
            $config['sign_url'],
            'POST',
            array('Content-Type: application/x-www-form-urlencoded'),
            $readingUrl
        );
        $signed = json_decode($signedBody, true);
        if (!is_array($signed) || !isset($signed['url'], $signed['headers'], $signed['key'])
            || !is_string($signed['url']) || !is_array($signed['headers'])
            || !is_string($signed['key'])) {
            throw new RuntimeException('签名服务响应缺少有效的 url、headers 或 key');
        }
        $signedParts = parse_url($signed['url']);
        if (!is_array($signedParts) || !isset($signedParts['scheme'], $signedParts['host'])
            || $signedParts['scheme'] !== 'https'
            || !preg_match('/(^|\.)fqnovel\.com$/i', $signedParts['host'])) {
            throw new RuntimeException('签名服务返回了不允许的阅读地址');
        }

        $headers = array();
        foreach ($signed['headers'] as $name => $value) {
            if (!is_string($name) || !is_string($value)
                || !preg_match('/^[!#$%&\'*+.^_`|~0-9A-Za-z-]+$/', $name)
                || preg_match('/[\r\n]/', $value)) {
                throw new RuntimeException('签名服务返回的 headers 格式无效');
            }
            $headers[] = $name . ': ' . $value;
        }

        $userAgent = isset($signed['ua']) && is_string($signed['ua'])
            ? $signed['ua'] : $config['reading_user_agent'];
        if (preg_match('/[\r\n]/', $userAgent)) {
            throw new RuntimeException('签名服务返回的 UA 含有无效换行');
        }
        $headers[] = 'x-xs-from-web: 0';
        $headers[] = 'User-Agent: ' . $userAgent;
        $headers[] = 'Cookie: ' . self::readCookie();

        return array(
            'response' => self::fetchJson($signed['url'], $headers, 2, $method, $body),
            'key' => $signed['key']
        );
    }

    private static function mapBatchReaderResponse($response, $itemIds, $key)
    {
        if (isset($response['code']) && (int) $response['code'] !== 0) {
            $message = isset($response['message'])
                ? (string) $response['message'] : '未知错误';
            throw new RuntimeException(
                '批量阅读接口错误：' . $message . ' (code=' . (int) $response['code'] . ')'
            );
        }
        if (!isset($response['data']) || !is_array($response['data'])) {
            throw new RuntimeException('批量阅读接口响应缺少章节数据');
        }

        $contents = array();
        foreach ($itemIds as $itemId) {
            if (!isset($response['data'][$itemId]) || !is_array($response['data'][$itemId])) {
                $contents[$itemId] = array(
                    'content' => '',
                    'error' => '批量阅读接口未返回本章内容'
                );
                continue;
            }
            $item = $response['data'][$itemId];
            if (!isset($item['content']) || !is_string($item['content'])
                || $item['content'] === '') {
                $contents[$itemId] = array(
                    'content' => '',
                    'error' => '阅读接口未返回本章正文'
                );
                continue;
            }
            try {
                $cryptStatus = isset($item['crypt_status'])
                    ? (int) $item['crypt_status'] : 0;
                $html = ($cryptStatus === 1 || $cryptStatus === 2)
                    ? $item['content'] : self::decryptContent($item['content'], $key);
                $contents[$itemId] = array(
                    'content' => self::contentText($html),
                    'error' => null
                );
            } catch (Exception $error) {
                $contents[$itemId] = array(
                    'content' => '',
                    'error' => $error->getMessage()
                );
            }
        }
        return $contents;
    }

    public static function getChapterContents($bookId, $itemIds)
    {
        $signedResult = self::signedReaderRequest(
            self::batchReadingUrl($bookId, $itemIds)
        );
        return self::mapBatchReaderResponse(
            $signedResult['response'],
            $itemIds,
            $signedResult['key']
        );
    }
}
