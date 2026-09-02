.PHONY: all classes jar dist plugin deploy install ci-libs clean help
.DELETE_ON_ERROR:

TARGET ?= 2026x

ifeq ($(TARGET),2026x)
RELEASE := 21
JDK_DEFAULT := /usr/lib/jvm/java-21-openjdk
AUTOMATON_VER := 2026x
CAMEO_DEFAULT := /workspace/MSOSA2026xHF2
else ifeq ($(TARGET),2024x)
RELEASE := 17
JDK_DEFAULT := /usr/lib/jvm/java-17-openjdk
AUTOMATON_VER := 2024x Refresh3
CAMEO_DEFAULT := /workspace/msosa2024
else
$(error Unknown target '$(TARGET)'. Valid: 2026x, 2024x)
endif

CAMEO_HOME ?= $(CAMEO_DEFAULT)
JDK_HOME ?= $(or $(JAVA_HOME),$(JDK_DEFAULT))
JAVAC := $(if $(wildcard $(JDK_HOME)/bin/javac),$(JDK_HOME)/bin/javac,javac)
JAR_TOOL := $(if $(wildcard $(JDK_HOME)/bin/jar),$(JDK_HOME)/bin/jar,jar)

VERSION ?= 0.1.1
PLUGIN_ID := com.haarer.saf.mcpserver
JAR_NAME := cameo-saf-mcp-server-$(VERSION).jar

BUILD_DIR := build
CLASSES_DIR := $(BUILD_DIR)/classes
LIBS_DIR := $(BUILD_DIR)/libs
DIST_DIR := $(BUILD_DIR)/plugin-dist/$(PLUGIN_ID)
PLUGIN_DIR := $(CAMEO_HOME)/plugins/$(PLUGIN_ID)
HEADERS_DIR := $(BUILD_DIR)/generated/sources/headers/java/main
ANNOTATION_DIR := $(BUILD_DIR)/generated/sources/annotationProcessor/java/main

space := $(empty) $(empty)
CAMEO_JARS := $(wildcard \
  $(CAMEO_HOME)/lib/com.nomagic.magicdraw.foundation-*.jar \
  $(CAMEO_HOME)/lib/com.nomagic.magicdraw.uml2-*.jar \
  $(CAMEO_HOME)/lib/com.nomagic.magicdraw.core.diagram-*.jar \
  $(CAMEO_HOME)/lib/com.nomagic.magicdraw.modeling-*.jar \
  $(CAMEO_HOME)/lib/com.nomagic.utils-*.jar \
  $(CAMEO_HOME)/lib/com.dassault_systemes.modeler.foundation-*.jar \
  $(CAMEO_HOME)/lib/core-*.jar \
  $(CAMEO_HOME)/lib/jackson-*.jar \
  $(CAMEO_HOME)/plugins/com.nomagic.magicdraw.automaton/lib/groovy-*.jar)
CP := $(subst $(space),:,$(CAMEO_JARS))
JAVAC_CP := $(if $(CP),-cp "$(CP)",)

SOURCES := $(shell find src -name '*.java' | sort)

all: jar

classes:
	rm -rf $(CLASSES_DIR)
	mkdir -p $(CLASSES_DIR) $(HEADERS_DIR) $(ANNOTATION_DIR)
	"$(JAVAC)" --release $(RELEASE) -d $(CLASSES_DIR) -h $(HEADERS_DIR) -g -sourcepath "" -proc:none -s $(ANNOTATION_DIR) -XDuseUnsharedTable=true $(JAVAC_CP) -Xlint:-deprecation -Xlint:-unchecked $(SOURCES)

jar: classes
	mkdir -p $(LIBS_DIR)
	rm -f $(LIBS_DIR)/$(JAR_NAME)
	"$(JAR_TOOL)" --create --file $(LIBS_DIR)/$(JAR_NAME) -C $(CLASSES_DIR) .

dist: jar
	rm -rf $(BUILD_DIR)/plugin-dist
	mkdir -p $(DIST_DIR)
	sed -e 's/$${version}/$(VERSION)/g' -e 's/$${automatonVersion}/$(AUTOMATON_VER)/g' plugin.xml > $(DIST_DIR)/plugin.xml
	cp $(LIBS_DIR)/$(JAR_NAME) $(DIST_DIR)/
	cp -r _data $(DIST_DIR)/_data

plugin: dist
	mkdir -p dist
	python3 ci/package-plugin.py --plugin-version $(VERSION) --target $(TARGET) --root .

deploy: dist
	mkdir -p "$(PLUGIN_DIR)"
	find "$(PLUGIN_DIR)" -maxdepth 1 -name 'cameo-saf-mcp-server-*.jar' ! -name '$(JAR_NAME)' -delete
	cp -r "$(DIST_DIR)/." "$(PLUGIN_DIR)/"

install: dist
	mkdir -p "$(PLUGIN_DIR)/scripts"
	find "$(PLUGIN_DIR)" -maxdepth 1 -name 'cameo-saf-mcp-server-*.jar' ! -name '$(JAR_NAME)' -delete
	cp -r "$(DIST_DIR)/." "$(PLUGIN_DIR)/"
	cp -r scripts/. "$(PLUGIN_DIR)/scripts/"
	cp -r _data/. "$(PLUGIN_DIR)/_data/"

ci-libs:
	bash ci/prepare-ci-libs.sh

clean:
	rm -rf $(BUILD_DIR)

help:
	@echo "make [TARGET=2026x|2024x] [VERSION=x.y.z] [CAMEO_HOME=/path] [JDK_HOME=/path]"
	@echo "  all/jar    compile and build $(JAR_NAME)"
	@echo "  dist       stage build/plugin-dist/$(PLUGIN_ID)"
	@echo "  plugin     package dist/cameo-saf-mcp-server.zip (no Gradle)"
	@echo "  deploy     deploy staged plugin files to $(PLUGIN_DIR)"
	@echo "  install    full deploy including scripts/"
	@echo "  ci-libs    prepare ci-libs for compile-only testing"
	@echo "  clean      remove $(BUILD_DIR)"
