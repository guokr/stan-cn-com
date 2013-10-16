stan-cn-com
===========

stan-cn-* package family is packages based on Stanford NLP packages for the
convenience of Chinese users. This package is a common base for stan-cn-*
package family.

stan-cn-* package family is including:

* stan-cn-com: common code base
* stan-cn-seg: Chinese segmentation
* stan-cn-ner: Naming entity recognization
* stan-cn-tag: POS tagging

Comments, reviews, bug reports and patches are welcomed.

Preparation for release
------------------------

Before release this package to maven central, please execute below commands:

* mvn clean source:jar javadoc:jar package

License
--------

GPLv2, just the same as the license of Stanford CoreNLP package

