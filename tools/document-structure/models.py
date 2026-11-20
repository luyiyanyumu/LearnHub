"""Fetch the five high-quality official models and verify their exact contents."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
from pathlib import Path, PurePosixPath
import shutil
import tarfile
from tempfile import TemporaryDirectory
import time
from urllib.request import urlopen

BASE_URL = "https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0"
FILES = ("inference.json", "inference.pdiparams", "inference.yml")
MODELS = {
    "PP-DocLayout_plus-L": (
        "99bd09c0a1b9e88d35c65c305b91711d98055fc94b4a6eecd50da02e04729384",
        "24ca3e2e442164505e250deef59f7ee9a54ea12dd32875c9cd6155d959dc97da",
        "d60f782a16f96afb27e8280399899a94c3e9ffc694ffb2f913ea00af1c522f1e",
    ),
    "PP-OCRv5_server_det": (
        "af5876933d8806a1b50d895867e0781e135cd92ff37381992828fc8d1b842d28",
        "183146fe9d9910352f68482f623bcbbb9fa7b9e8fa1463b9ad288cef00524d2d",
        "28fb721efc3634fc8aa677e474b9602cb815a91cf569ef357a7a553d7b3ce685",
    ),
    "PP-OCRv5_server_rec": (
        "8e6e12e5d42531840310977fffb58165bf889fc5061408c5a8afdb6985f47fcb",
        "63853f062a5f4089befc16f565a68277618e0da5cb45468b49d11079de0ada77",
        "2c719dba044c4e2228aef8ff92f5f575394d75d24c16de096a33b7cfd902f66d",
    ),
    "PP-FormulaNet_plus-L": (
        "ad259c4b896d99aa3479336b9121112fb40ff1ababfbf8765a3428a3b86df582",
        "4245c39c181d1d21e472bc85c7434df9b23f177be46552c0542bf153addbc355",
        "afc92a2737268da0499c37b0b6741da268c369fd7424667fcfeb8fa6c7b22d30",
    ),
    "SLANet_plus": (
        "aba91cd956f4a1648b0f89df94c3cf8acaa066b47e2b689d109843b40d8545a2",
        "012986b0e2bfe90410618bfb4b7cf4fcb6c978caefd84c63c2934f8387b09ab8",
        "8a6372d3269a6f112fe13a2da7952a84da6e112c10a3146cbb43de5bd01d19fa",
    ),
}
MAX_ARCHIVE_BYTES = 1024 * 1024 * 1024


def verify(directory, model):
    for name, checksum in zip(FILES, MODELS[model]):
        with (directory / name).open("rb") as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() != checksum:
                raise ValueError(f"{model}/{name} checksum mismatch")


def fetch(target, model):
    with TemporaryDirectory(dir=target) as scratch:
        scratch = Path(scratch)
        archive = scratch / "model.tar"
        for attempt in range(3):
            try:
                with urlopen(f"{BASE_URL}/{model}_infer.tar", timeout=60) as response, archive.open("wb") as output:
                    size = 0
                    while chunk := response.read(1024 * 1024):
                        size += len(chunk)
                        if size > MAX_ARCHIVE_BYTES:
                            raise ValueError(f"{model} archive exceeded its size limit")
                        output.write(chunk)
                break
            except OSError:
                if attempt == 2:
                    raise
                time.sleep(1 << attempt)
        directory = scratch / model
        directory.mkdir()
        with tarfile.open(archive, "r:*") as contents:
            for member in contents:
                name = PurePosixPath(member.name).name
                if name in FILES and member.isfile() and 0 < member.size <= MAX_ARCHIVE_BYTES:
                    with contents.extractfile(member) as stream, (directory / name).open("xb") as output:
                        shutil.copyfileobj(stream, output)
        verify(directory, model)
        directory.rename(target / model)
        print(f"{model} verified", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("target", type=Path)
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    if args.verify:
        for model in MODELS:
            verify(args.target / model, model)
    else:
        args.target.mkdir(parents=True, exist_ok=True)
        with ThreadPoolExecutor(max_workers=3) as pool:
            list(pool.map(lambda model: fetch(args.target, model), MODELS))
