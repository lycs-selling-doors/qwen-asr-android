"""Exercise the Android gateway with real SDKs and a synthetic speech fixture."""

import argparse
import base64
import io
import os
from pathlib import Path
import time
import wave
import urllib.request

import dashscope
from openai import OpenAI


def speech_wav() -> bytes:
    repo = Path(__file__).resolve().parents[1]
    pcm = (repo / "app/src/main/assets/probe-en.pcm").read_bytes()
    wav = io.BytesIO()
    with wave.open(wav, "wb") as stream:
        stream.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
        stream.writeframes(pcm)
    return wav.getvalue()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("local_port", type=int)
    args = parser.parse_args()
    base = f"http://127.0.0.1:{args.local_port}"
    os.environ["NO_PROXY"] = "localhost,127.0.0.1"
    auth = "Basic " + base64.b64encode(b"admin:fixture-basic").decode("ascii")
    health = urllib.request.Request(base + "/api/v1/health", headers={"Authorization": auth})
    for attempt in range(40):
        try:
            with urllib.request.urlopen(health, timeout=1) as response:
                if b'"ready":true' in response.read():
                    break
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(0.5)
    else:
        raise RuntimeError("The instrumentation gateway did not become ready")

    model = "qwen3-asr-1.7b-q8_0"
    wav = speech_wav()
    with OpenAI(base_url=base + "/v1", api_key="fixture-openai", max_retries=0, timeout=40) as client:
        assert model in {item.id for item in client.models.list()}
        result = client.audio.transcriptions.create(
            model=model, file=("probe.wav", wav, "audio/wav"), language="en"
        )
        assert "speech" in result.text.lower()
    print("OpenAI Python SDK: models.list and audio.transcriptions passed")

    dashscope.base_http_api_url = base + "/api/v1"
    encoded = "data:audio/wav;base64," + base64.b64encode(wav).decode("ascii")
    result = dashscope.MultiModalConversation.call(
        model="qwen3-asr-flash", api_key="fixture-dash",
        messages=[{"role": "user", "content": [{"audio": encoded}]}],
        asr_options={"language": "en", "enable_itn": False},
    )
    assert result.status_code == 200, (result.status_code, result.code, result.message)
    assert "speech" in result.output["choices"][0]["message"]["content"][0]["text"].lower()
    print("DashScope Python SDK: MultiModalConversation.call passed")


if __name__ == "__main__":
    main()
