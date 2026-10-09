# Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Runs a small Burr application several times with Burr's own LocalTrackingClient, so the tracking
server has real traces to serve. No model is called: the two actions stand in for "retrieve" and
"answer", and the state they write is what a real agent's trace would carry (a question, a retrieved
document, an answer).

  python record_runs.py <storage_dir> <app_id> [<app_id> ...]
"""
import sys

from burr.core import ApplicationBuilder, State, action
from burr.tracking import LocalTrackingClient


@action(reads=["question"], writes=["document"])
def retrieve(state: State) -> State:
    return state.update(document="Refund policy: orders can be returned within 30 days.")


@action(reads=["question", "document"], writes=["answer"])
def answer_question(state: State) -> State:
    return state.update(answer="Yes, within 30 days of the order.")


def main() -> None:
    storage, app_ids = sys.argv[1], sys.argv[2:]
    for app_id in app_ids:
        app = (
            ApplicationBuilder()
            .with_actions(retrieve, answer_question)
            .with_transitions(("retrieve", "answer_question"))
            .with_state(question="Can customer 4711 still return order 991?")
            .with_entrypoint("retrieve")
            .with_identifiers(app_id=app_id)
            .with_tracker(LocalTrackingClient(project="support-agent", storage_dir=storage))
            .build()
        )
        app.run(halt_after=["answer_question"])
        print("recorded", app_id)


if __name__ == "__main__":
    main()
