import { useState } from 'react';
import { postQuery } from '../api/dashboard';
import { ApiError } from '../api/client';
import type { QueryAnswer } from '../api/types';
import { PageHeader } from '../components/Layout';
import { Working } from '../components/Working';

/**
 * Questions drawn from ones that actually work against this data, rather than invented
 * examples - one for each path, so the first click shows the system doing something real.
 */
const STARTERS = [
  'how much did I spend on food',
  'how much did I pay for my flight booking',
];

interface Turn {
  id: number;
  question: string;
  answer?: QueryAnswer;
  /** A transport failure. An answer the service declined to give arrives in `answer.error`. */
  failure?: unknown;
  pending: boolean;
}

export function ChatPage() {
  // Held in component state only. The history is the session's, and deliberately not persisted:
  // nothing here is worth keeping, and storing questions about someone's spending is a cost
  // with no benefit.
  const [turns, setTurns] = useState<Turn[]>([]);
  const [input, setInput] = useState('');
  const [pending, setPending] = useState(false);

  async function ask(question: string) {
    const trimmed = question.trim();
    if (!trimmed || pending) return;

    const id = Date.now();
    setTurns((prev) => [...prev, { id, question: trimmed, pending: true }]);
    setInput('');
    setPending(true);

    try {
      const answer = await postQuery(trimmed);
      setTurns((prev) => prev.map((t) => (t.id === id ? { ...t, answer, pending: false } : t)));
    } catch (failure) {
      setTurns((prev) => prev.map((t) => (t.id === id ? { ...t, failure, pending: false } : t)));
    } finally {
      setPending(false);
    }
  }

  return (
    <>
      <PageHeader
        title="Chat"
        description="Ask a question in plain language. Every answer shows the work behind it."
      />

      {turns.length === 0 && (
        <div className="starters">
          <p className="state__detail">Questions that work well against this data:</p>
          {STARTERS.map((s) => (
            <button key={s} type="button" className="button starter" onClick={() => ask(s)}>
              {s}
            </button>
          ))}
        </div>
      )}

      <ol className="turns">
        {turns.map((turn) => (
          <li className="turn" key={turn.id}>
            <p className="turn__question">{turn.question}</p>

            {turn.pending && (
              <p className="turn__pending" role="status">
                <span className="dots" aria-hidden="true">
                  <span />
                  <span />
                  <span />
                </span>
                Routing the question, resolving merchants and querying — this takes a few
                seconds.
              </p>
            )}

            {turn.failure !== undefined && <TurnFailure failure={turn.failure} />}

            {turn.answer && (
              <>
                {turn.answer.error ? (
                  // A 200 with an error field: the service understood the request and declined
                  // to answer it. Not a failure of the UI or the network, so it is not styled
                  // as one.
                  <p className="turn__declined">{turn.answer.error}</p>
                ) : (
                  <>
                    <p className="turn__answer">{turn.answer.answer}</p>
                    <Working answer={turn.answer} />
                  </>
                )}
              </>
            )}
          </li>
        ))}
      </ol>

      <form
        className="ask"
        onSubmit={(e) => {
          e.preventDefault();
          ask(input);
        }}
      >
        <input
          className="field__input ask__input"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="How much did I spend on groceries last month?"
          // Disabled while in flight so a second submit cannot race the first: each question
          // costs four Gemini calls, and two answers arriving out of order would be worse than
          // waiting.
          disabled={pending}
          aria-label="Your question"
        />
        <button type="submit" className="button button--primary" disabled={pending || !input.trim()}>
          {pending ? 'Asking…' : 'Ask'}
        </button>
      </form>
    </>
  );
}

function TurnFailure({ failure }: { failure: unknown }) {
  const apiError = failure instanceof ApiError ? failure : null;
  return (
    <div className="turn__failure" role="alert">
      <p>{apiError?.message ?? 'The question could not be sent.'}</p>
      {apiError?.correlationId && (
        <code className="state__trace">correlationId: {apiError.correlationId}</code>
      )}
    </div>
  );
}
