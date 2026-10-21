import os
import subprocess
import sys

import requests


SIGN_URL = "http://127.0.0.1:18090/sign"
AUDIO_API_TEMPLATE = (
    "https://api5-normal-sinfonlinea.fqnovel.com/reading/reader/audio/playinfo/"
    "?tone_id=1&item_ids={chapter_id}&pv_player=-1&aid=1967"
)
USER_AGENT = (
    "com.dragon.read/73932 (Linux; U; Android 12; zh_CN; V2362A; "
    "Build/V417IR;tt-ok/3.12.13.20)"
)


def get_audio_url(chapter_id):
    audio_url = AUDIO_API_TEMPLATE.format(chapter_id=chapter_id)
    sign_response = requests.post(
        SIGN_URL,
        data=audio_url.encode("utf-8"),
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        timeout=30,
    )
    sign_response.raise_for_status()

    try:
        signed = sign_response.json()
    except ValueError as error:
        body_preview = sign_response.text[:200].replace("\n", " ")
        raise RuntimeError(
            f"签名服务响应不是有效 JSON：{body_preview!r}"
        ) from error

    signed_url = signed.get("url")
    headers = signed.get("headers")
    if not isinstance(signed_url, str) or not isinstance(headers, dict):
        raise RuntimeError("签名服务响应缺少有效的 url 或 headers")
    if not all(isinstance(name, str) and isinstance(value, str) for name, value in headers.items()):
        raise RuntimeError("签名服务返回的 headers 格式无效")

    request_headers = dict(headers)
    request_headers["x-xs-from-web"] = "0"
    request_headers["user-agent"] = USER_AGENT
    cookie = os.environ.get("FQ_COOKIE")
    if cookie:
        request_headers["cookie"] = cookie

    response = requests.get(signed_url, headers=request_headers, timeout=30)
    response.raise_for_status()
    if not response.content.strip():
        raise RuntimeError("签名后的音频接口返回空响应")

    try:
        data = response.json()
    except ValueError as error:
        body_preview = response.text[:200].replace("\n", " ")
        raise RuntimeError(
            f"签名后的音频接口响应不是有效 JSON：{body_preview!r}"
        ) from error

    if data.get("code") != 0:
        raise RuntimeError(
            f"音频接口返回错误（code={data.get('code')}）："
            f"{data.get('message', '未知错误')}"
        )
    print(data)
    audio_items = data.get("data")
    if not audio_items or not audio_items[0].get("main_url"):
        raise RuntimeError("未获取到该章节的音频地址")

    return audio_items[0]["main_url"]


def main():
    chapter_id = "7634948781665960473,7691502742778413593"

    try:
        audio_url = get_audio_url(chapter_id)
    except (requests.RequestException, RuntimeError) as error:
        print(f"获取音频 URL 失败：{error}", file=sys.stderr)
        return 1

    print(audio_url)
    try:
        subprocess.run(["ffplay", audio_url], check=True)
    except (OSError, subprocess.CalledProcessError) as error:
        print(f"播放音频失败：{error}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
