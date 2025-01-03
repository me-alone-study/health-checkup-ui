from flask import Flask, request, jsonify
import os
from werkzeug.utils import secure_filename
import datetime

app = Flask(__name__)

# 配置文件上传参数
UPLOAD_FOLDER = 'uploads'  # 上传文件保存的目录
ALLOWED_EXTENSIONS = {'mp4'}  # 允许上传的文件类型
MAX_CONTENT_LENGTH = 16 * 1024 * 1024  # 最大文件大小限制（16MB）

app.config['UPLOAD_FOLDER'] = UPLOAD_FOLDER

# 确保上传目录存在
os.makedirs(UPLOAD_FOLDER, exist_ok=True)

def allowed_file(filename):
    """检查文件类型是否允许上传"""
    return '.' in filename and filename.rsplit('.', 1)[1].lower() in ALLOWED_EXTENSIONS

@app.route('/upload', methods=['POST'])
def upload_file():
    try:
        # 检查是否有文件被上传
        if 'video' not in request.files:
            return jsonify({
                'status': 'error',
                'message': 'No video file provided',
                'error_code': 400
            }), 400
        
        file = request.files['video']
        
        # 检查文件名是否为空
        if file.filename == '':
            return jsonify({
                'status': 'error',
                'message': 'No selected file',
                'error_code': 400
            }), 400
        
        # 检查文件类型是否允许
        if not allowed_file(file.filename):
            return jsonify({
                'status': 'error',
                'message': 'File type not allowed',
                'error_code': 400
            }), 400
        
        # 安全地保存文件
        filename = secure_filename(file.filename)
        # 添加时间戳到文件名
        timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        new_filename = f"{timestamp}_{filename}"
        file_path = os.path.join(app.config['UPLOAD_FOLDER'], new_filename)
        file.save(file_path)
        
        # 获取文件大小
        file_size = os.path.getsize(file_path)
        
        # 返回成功响应
        return jsonify({
            'status': 'success',
            'message': 'File uploaded successfully',
            'data': {'age': 20, 'age_group': 'Adult', 'blood_pressure': {'dbp': 74, 'sbp': 120}, 'gender': 'Male', 'heart_rate': {'cv_rr': 0.0, 'rate': 63, 'rmssd': 0.0, 'sdnn': 0.0}, 'processing_time': 17.17, 'respiration': {'details': {'RR_CP': 29.00066212981278, 'RR_FFT': 23.278123448679157, 'RR_NFCP': 29.00066212981278, 'RR_PC': 0.0}, 'rate': 20}, 'spo2': 97.15}
        }), 200
        
    except Exception as e:
        # 处理可能的错误
        return jsonify({
            'status': 'error',
            'message': str(e),
            'error_code': 500
        }), 500

@app.errorhandler(413)
def request_entity_too_large(error):
    """处理文件过大的错误"""
    return jsonify({
        'status': 'error',
        'message': 'File is too large',
        'error_code': 413
    }), 413

if __name__ == '__main__':
    app.run(debug=True, host='0.0.0.0', port=5000)