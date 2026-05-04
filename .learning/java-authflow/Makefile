.PHONY: build run start stop status clean test

APP_NAME=authflow
JAR_FILE=target/authflow-1.0.0.jar
PID_FILE=/tmp/$(APP_NAME).pid

build:
	mvn clean package -DskipTests

run: build
	REDIS_PASSWORD=$(REDIS_PASSWORD) java -jar $(JAR_FILE)

start: build
	@if [ -f $(PID_FILE) ] && kill -0 $$(cat $(PID_FILE)) 2>/dev/null; then \
		echo "Application is already running (PID: $$(cat $(PID_FILE)))"; \
	else \
		nohup sh -c 'REDIS_PASSWORD=$(REDIS_PASSWORD) java -jar $(JAR_FILE)' > /tmp/$(APP_NAME).log 2>&1 & \
		echo $$! > $(PID_FILE); \
		echo "Application started (PID: $$!)"; \
	fi

stop:
	@if [ -f $(PID_FILE) ]; then \
		PID=$$(cat $(PID_FILE)); \
		if kill -0 $$PID 2>/dev/null; then \
			kill $$PID; \
			echo "Application stopped (PID: $$PID)"; \
		else \
			echo "Application not running"; \
		fi; \
		rm -f $(PID_FILE); \
	else \
		echo "No PID file found"; \
	fi

status:
	@if [ -f $(PID_FILE) ] && kill -0 $$(cat $(PID_FILE)) 2>/dev/null; then \
		echo "Application is running (PID: $$(cat $(PID_FILE)))"; \
	else \
		echo "Application is not running"; \
		rm -f $(PID_FILE) 2>/dev/null; \
	fi

clean:
	mvn clean

test:
	mvn test
