import subprocess
import tempfile
import time
from pathlib import Path

from fastapi import FastAPI
from pydantic import BaseModel, Field


class RunRequest(BaseModel):
    code: str = Field(min_length=1)
    stdin: str = ""
    timeoutSeconds: int = Field(default=3, ge=1, le=10)


class RunResponse(BaseModel):
    stdout: str
    stderr: str
    exitCode: int | None
    timedOut: bool
    durationMillis: int


app = FastAPI(title="CodeReferee Python Sandbox")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/run", response_model=RunResponse)
def run_code(request: RunRequest) -> RunResponse:
    started_at = time.monotonic()

    with tempfile.TemporaryDirectory(prefix="codereferee-") as workspace:
        code_path = Path(workspace) / "main.py"
        code_path.write_text(request.code, encoding="utf-8")

        try:
            completed = subprocess.run(
                ["python", "-I", str(code_path)],
                input=request.stdin,
                capture_output=True,
                text=True,
                timeout=request.timeoutSeconds,
                cwd=workspace,
                check=False,
            )

            return RunResponse(
                stdout=completed.stdout,
                stderr=completed.stderr,
                exitCode=completed.returncode,
                timedOut=False,
                durationMillis=int((time.monotonic() - started_at) * 1000),
            )
        except subprocess.TimeoutExpired as exc:
            return RunResponse(
                stdout=exc.stdout or "",
                stderr=(exc.stderr or "") + "\nExecution timed out.",
                exitCode=None,
                timedOut=True,
                durationMillis=int((time.monotonic() - started_at) * 1000),
            )
