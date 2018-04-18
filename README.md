# Large S3 File Output Plugin for Embulk
Embulk File Output Plugin: Handle and Upload really large files to AWS S3 using multipart upload.
This plugin is an extension of classic s3 output plugin. [https://github.com/llibra/embulk-output-s3] 
## Developers

* Angelos Alexopoulos <alexopoulos7@gmail.com>

## Overview

* **Plugin type**: output
* **Load all or nothing**: no
* **Resume supported**: no
* **Cleanup supported**: yes

## Configuration

- **path_prefix**: prefix of target keys (string, required)
- **file_ext**: suffix of target keys (string, required)
- **sequence_format**: format for sequence part of target keys (string, default: '.%03d.%02d')
- **bucket**: S3 bucket name (string, required)
- **endpoint**: S3 endpoint login user name (string, optional)
- **access_key_id**: AWS access key id. This parameter is required when your agent is not running on EC2 instance with an IAM Role. (string, defualt: null)
- **secret_access_key**: AWS secret key. This parameter is required when your agent is not running on EC2 instance with an IAM Role. (string, defualt: null)
- **tmp_path**: temporary file directory. If null, it is associated with the default FileSystem. (string, default: null)
- **tmp_path_prefix**: prefix of temporary files (string, default: 'embulk-output-s3-')
- **canned_acl**: canned access control list for created objects ([enum](#cannedaccesscontrollist), default: null)
- **proxy_host**: proxy host to use when accessing AWS S3 via proxy. (string, default: null )
- **proxy_port**: proxy port to use when accessing AWS S3 via proxy. (string, default: null )

### CannedAccessControlList
you can choose one of the below list.

- AuthenticatedRead
- AwsExecRead
- BucketOwnerFullControl
- BucketOwnerRead
- LogDeliveryWrite
- Private
- PublicRead
- PublicReadWrite

cf. http://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/services/s3/model/CannedAccessControlList.html

## Example

```yaml
out:
  type: larges3
  path_prefix: logs/out
  file_ext: .csv
  bucket: my-s3-bucket
  endpoint: s3-us-west-1.amazonaws.com
  access_key_id: ABCXYZ123ABCXYZ123
  secret_access_key: AbCxYz123aBcXyZ123
  formatter:
    type: csv
```


## Build

```
$ ./gradlew gem
```