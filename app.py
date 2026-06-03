from flask import Flask, jsonify, request
from openai import OpenAI, APIStatusError, AuthenticationError, RateLimitError
from dotenv import load_dotenv
import os

load_dotenv()

client = OpenAI(api_key=os.getenv("OPENAI_API_KEY"))

conversation_store = {}

app = Flask(__name__)

@app.route("/")
def home():
    return "Flask is running!"

@app.route("/ask", methods=["POST"])
def ask():
    try:
        data = request.get_json()

        session_id = data["session_id"]
        user_input = data["input"]

        if session_id not in conversation_store:
            conversation_store[session_id] = []
        
        conversation_store[session_id].append({ "role": "user", "content": user_input})

        response = client.responses.create(
            model="gpt-4o-mini",
            input=conversation_store[session_id],
        )

        conversation_store[session_id].append({
            "role": "assistant",
            "content": response.output_text
        })

        return jsonify({
            "role": "assistant",
            "response": response.output_text,
            "history": conversation_store[session_id]
        })

    except AuthenticationError as e:
        print("AUTHENTICATION ERROR:", e)
        return jsonify({"error": str(e)}), 401

    except RateLimitError as e:
        print("RATE LIMIT / QUOTA ERROR:", e)
        return jsonify({"error": str(e)}), 429

    except APIStatusError as e:
        print("OPENAI API STATUS ERROR")
        print("Status Code:", e.status_code)
        print("Response:", e.response.text)
        return jsonify({
            "status_code": e.status_code,
            "error": e.response.text
        }), e.status_code

    except Exception as e:
        print("GENERAL ERROR:", type(e), e)
        return jsonify({"error": str(e)}), 500

if __name__ == "__main__":
    app.run(debug=True)